package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.engine.routing.RoutePlanner;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Ingredient;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.vehicle.TransitBoarding;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.RoutePlan;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Phase-12 dispatcher. On every simulation tick, asks {@link GameState} to:
 * <ol>
 *   <li>spawn a robot for each restaurant order with no in-flight vehicle, and</li>
 *   <li>top up each factory's input stockpile from the nearest producer.</li>
 * </ol>
 * Replaces the old frontend {@code DeliveryService} dispatch logic — the server is now
 * the source of truth for "where every shipment is right now". Frontend just renders.
 *
 * <p>Lives outside {@code sim/} so the framework-agnostic core stays pure (per the
 * README rule); the dispatcher itself is a thin Spring bean over
 * {@link GameState#spawnRobot}.
 *
 * <p>Phase-14 perf: recipe-graph derivatives ({@code recipesById},
 * {@code recipesByOutput}, transitive-input closures) are immutable for the lifetime
 * of the {@link ContentRegistry} and are cached lazily on first dispatch instead of
 * being rebuilt every tick. Building lookup is also pre-indexed into a
 * {@code UUID → Building} map per-tick to drop the per-order O(n) {@code findById} scan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleDispatcher {

	private final GameState gameState;
	private final ContentRegistry registry;
	private final RoutePlanner routePlanner;

	/** Recipe lookup by id. Eager-built in {@link #buildRecipeIndices()} after Spring
	 * has finished wiring the {@link ContentRegistry}. Immutable post-load. */
	private final Object2ObjectOpenHashMap<String, Recipe> recipesById = new Object2ObjectOpenHashMap<>();
	/** {@code outputIngredientId → producing recipes}. Eager-built. Immutable post-load. */
	private final Object2ObjectOpenHashMap<String, ObjectList<Recipe>> recipesByOutput = new Object2ObjectOpenHashMap<>();
	/** Memoised: {@code recipeId → set of every ingredient consumed transitively}. */
	private final Object2ObjectOpenHashMap<String, ObjectSet<String>> transitiveInputClosureByRecipe =
		new Object2ObjectOpenHashMap<>();

	// Phase-17 diagnostics: surface when the long-haul (multi-leg) path is reached
	// and whether the planner produced a chain. Without these the operator can't
	// tell whether buses aren't spawning because no order ever has a beyond-cap
	// producer (small map / nearby supply) versus the planner failing on every
	// attempt (no GTFS feed, no nearby stops, no connecting trip, …).
	private final AtomicLong multiLegAttempts = new AtomicLong();
	private final AtomicLong multiLegSpawned = new AtomicLong();
	private final AtomicLong multiLegPlanEmpty = new AtomicLong();
	private final AtomicLong multiLegSpawnEmpty = new AtomicLong();
	private final AtomicLong robotShortLegSpawns = new AtomicLong();
	private final AtomicLong robotShortLegEmpty = new AtomicLong();
	private final AtomicLong restockAttempts = new AtomicLong();
	private final AtomicLong restockNoProducerSamples = new AtomicLong();
	private final AtomicLong restockSpawnEmpty = new AtomicLong();
	private final AtomicLong restockRecipeMissing = new AtomicLong();
	private final AtomicLong restockRecipeNoInputs = new AtomicLong();
	private final AtomicLong restockTicksObserved = new AtomicLong();
	private final AtomicLong factoriesSeenAccumulator = new AtomicLong();
	private final AtomicLong factoriesWithInputsAccumulator = new AtomicLong();
	private final AtomicLong lastReportedAttempts = new AtomicLong();
	/**
	 * Phase-21: count of dispatch attempts where {@link RoutePlan} resolves
	 * to {@link RoutePlan.NoPath} (no transit chain AND haversine ≥ 5 km,
	 * OR routable but the OSM path itself was unreachable). Surfaced in the
	 * periodic dispatcher summary so the operator can tell apart "no
	 * producer" / "stock race" / "unreachable" failure modes. Producer
	 * stock is never debited in this branch — see
	 * {@link com.dimsumdetours.engine.VehicleDispatcherFailedDispatchTest}.
	 */
	private final AtomicLong failedDispatches = new AtomicLong();
	/** Phase-21 fix: per-tuple last-log wall-clock timestamp. The previous
	 * counter-bucket throttle ({@code lastFailedDispatchLogged} CAS modulo
	 * {@link #FAILED_DISPATCH_LOG_INTERVAL}) flooded the log: on a map with
	 * many chronically-stalled orders the dispatcher fires dozens of fails
	 * per tick, crossing the bucket boundary multiple times per tick and
	 * emitting one log line per crossing. Keying on the actual stalled
	 * shipment lets each unique (producer, consumer, ingredient) triple log
	 * at most once per {@link #NO_PATH_LOG_INTERVAL_MS} of wall time. */
	private final ConcurrentHashMap<NoPathKey, Long> noPathLastLogWallMs = new ConcurrentHashMap<>();
	private static final long DISPATCHER_REPORT_INTERVAL = 50L;
	/** Wall-clock minimum interval between repeated NoPath log lines for the
	 * same (producer, consumer, ingredient) triple. Long enough that a stuck
	 * map doesn't flood logs; short enough that the operator notices a fresh
	 * stall within a minute of it appearing. */
	private static final long NO_PATH_LOG_INTERVAL_MS = 60_000L;

	/** Throttle key: stable across dispatcher attempts for a given stalled
	 * shipment, so the same chronic stall logs once per minute rather than
	 * once per attempt. */
	private record NoPathKey(UUID producerId, UUID consumerId, String ingredientId) {}


	/**
	 * Build the recipe-graph caches once Spring has fully wired the context.
	 *
	 * <p>Lifecycle ordering: {@link com.dimsumdetours.content.ContentLoader#loadAll}
	 * runs at {@link ApplicationReadyEvent} too, so we declare an explicit
	 * {@link Order#LOWEST_PRECEDENCE} (the loader is unannotated and gets the
	 * default precedence, which sorts before us). A previous version used
	 * {@code @PostConstruct} here and ran <em>before</em> the loader, leaving
	 * {@code recipesById} permanently empty for the whole session — every factory
	 * was reported as {@code [restock/recipe-missing]}.
	 *
	 * <p>Defensive: also called lazily from {@link #dispatch} when the cache is
	 * still empty (e.g. test contexts that don't fire {@link ApplicationReadyEvent}).
	 */
	@EventListener(ApplicationReadyEvent.class)
	@org.springframework.core.annotation.Order(Ordered.LOWEST_PRECEDENCE)
	void buildRecipeIndices() {
		recipesById.clear();
		recipesByOutput.clear();
		transitiveInputClosureByRecipe.clear();
		for (Recipe recipe : registry.allRecipes()) {
			recipesById.put(recipe.id(), recipe);
			for (RecipeIngredient output : recipe.outputs()) {
				recipesByOutput.computeIfAbsent(output.ingredientId(), id -> new ObjectArrayList<>()).add(recipe);
			}
		}
		if (recipesById.isEmpty()) {
			log.warn("VehicleDispatcher: recipe index built but registry was empty — "
				+ "ContentLoader either didn't run or loaded no recipes. Restocks will be a no-op.");
		} else {
			log.info("VehicleDispatcher: recipe index ready, {} recipes loaded", recipesById.size());
		}
	}

	/**
	 * One pass over restaurant orders + factory stockpiles. Returns the list of spawn
	 * events for the engine to broadcast onto the SSE stream. With the Phase-14 caches
	 * the per-tick cost is O(orders × producers) + O(factories × inputs × producers),
	 * with each factor walking a small {@link ObjectArrayList} rather than allocating
	 * any per-tick HashMaps.
	 */
	// fastutil's get() returns null on miss, but its type isn't @NullMarked so IntelliJ
	// reads it as @NonNull — keep the defensive null guards for content-hot-reload safety.
	@SuppressWarnings("ConstantValue")
	public List<VehicleEvent.Spawned> dispatch() {
		// Defensive lazy init: if the ApplicationReadyEvent listener never fired
		// (test contexts, embedded boots) try to populate the cache from the
		// current registry state — content may have been loaded by now.
		if (recipesById.isEmpty() && registry.recipeCount() > 0) {
			buildRecipeIndices();
		}
		List<Building> buildings = gameState.listBuildings();
		ObjectList<VehicleEvent.Spawned> spawned = new ObjectArrayList<>();

		// 1. Restaurant-order dispatch. Phase-21: every order asks the multi-leg
		// planner for a chain. Per docs/DISPATCH.md "Transit is always preferred
		// when both a transit plan and a direct robot plan exist", so we no
		// longer compare durations — if the planner returns a chain, use it.
		// Direct robot only fires as a fallback when (a) the planner returns no
		// chain AND (b) the source-to-destination straight-line distance is
		// within MAX_ROBOT_LEG_METERS. Otherwise the dispatch is skipped and
		// retried next tick (the producer keeps its stock).
		for (Order order : gameState.listAllOrders()) {
			if (gameState.hasInFlightOrder(order.id())) {
				continue;
			}
			Building restaurant = findById(buildings, order.restaurantId());
			if (!(restaurant instanceof Restaurant)) {
				continue;
			}
			Building source = pickNearestSourceForOrder(buildings, order, restaurant);
			if (source == null) {
				continue;
			}
			Recipe sourceRecipe = recipesById.get(source.recipeId());
			Long spoilageDeadline = computeSpoilageDeadline(source, sourceRecipe);
			String cargoIngredient = (sourceRecipe != null && !sourceRecipe.outputs().isEmpty())
				? sourceRecipe.outputs().getFirst().ingredientId()
				: source.outputIngredientId();
			if (cargoIngredient == null) {
				continue;
			}
			double sourceDistance = GameState.haversineMetres(
				source.lat(), source.lon(), restaurant.lat(), restaurant.lon());
			dispatchShipment(spawned, source, restaurant, cargoIngredient,
				GameConstants.ROBOT_CARGO_BATCH_SIZE,
				order.id(), spoilageDeadline, sourceDistance, ShipmentKind.ORDER);
		}

		// 2. Factory-restock dispatch. Same fastest-option pattern as orders.
		long factoriesSeen = 0L;
		long factoriesWithInputs = 0L;
		for (Building building : buildings) {
			if (!(building instanceof Factory factory)) {
				continue;
			}
			factoriesSeen++;
			Recipe recipe = recipesById.get(factory.recipeId());
			if (recipe == null) {
				long n = restockRecipeMissing.incrementAndGet();
				if (n <= 5 || n % 100L == 0L) {
					log.info("[restock/recipe-missing {}] factory {} has recipeId='{}' "
							+ "but no such recipe is loaded — restock skipped",
						n, factory.id(), factory.recipeId());
				}
				continue;
			}
			if (recipe.inputs().isEmpty()) {
				long n = restockRecipeNoInputs.incrementAndGet();
				if (n <= 5 || n % 200L == 0L) {
					log.info("[restock/recipe-no-inputs {}] factory {} runs recipe '{}' which "
							+ "has no inputs — nothing to restock (this is fine for harvest-style "
							+ "recipes, but suspect if you expected ingredient flow)",
						n, factory.id(), factory.recipeId());
				}
				continue;
			}
			factoriesWithInputs++;
			for (RecipeIngredient input : recipe.inputs()) {
				int have = factory.inputStockpile().getOrDefault(input.ingredientId(), 0);
				int target = Math.max(
					input.quantity() * GameConstants.FACTORY_RESTOCK_TARGET_CYCLES,
					GameConstants.ROBOT_CARGO_BATCH_SIZE);
				if (have >= target) {
					continue;
				}
				Building producer = nearestProducerOf(buildings, factory, input.ingredientId());
				if (producer == null) {
					// Phase-19 diag: the most common silent-stall cause. The factory
					// wants {@code input.ingredientId()} but no farm/factory on the
					// map has produced any units of it yet (or the only producers
					// also happen to be this factory itself).
					long n = restockNoProducerSamples.incrementAndGet();
					if (n <= 5 || n % 50L == 0L) {
						log.info("[restock/no-producer {}] factory {} needs {} (have {}/{}) — "
								+ "no producer with ≥ 1 unit on the map yet",
							n, factory.id(), input.ingredientId(), have, target);
					}
					continue;
				}
				if (gameState.hasActiveRestock(producer.id(), factory.id(), input.ingredientId())) {
					continue;
				}
				double producerDistance = GameState.haversineMetres(
					producer.lat(), producer.lon(), factory.lat(), factory.lon());
				// Phase-19 fix: ship whatever's available up to a full batch. The earlier
				// "producer must have ≥ batch units" gate meant a fresh farm with a
				// 90-game-minute cycle had to wait 7.5 game-hours before its first
				// restock could fire — long enough that players reasonably concluded
				// "ingredients aren't travelling from farms to factories at all". For
				// restock the smaller-truck-than-full case is fine; we just send what's
				// there. Restaurant orders still ship the full 5-unit batch (their
				// branch checks {@link #hasStock} which preserves the Phase-17 rule).
				int quantity = Math.min(producerStock(producer), GameConstants.ROBOT_CARGO_BATCH_SIZE);
				long attempt = restockAttempts.incrementAndGet();
				if (attempt <= 10 || attempt % 25L == 0L) {
					log.info("[restock/attempt {}] producer {} ({} units of {}) → factory {} "
							+ "(have {}/{}); shipping {} units (dispatcher prefers transit when planner returns a chain)",
						attempt, producer.id(), producerStock(producer), input.ingredientId(),
						factory.id(), have, target, quantity);
				}
				dispatchShipment(spawned, producer, factory, input.ingredientId(),
					quantity, null, null, producerDistance, ShipmentKind.RESTOCK);
			}
		}

		// Phase-19 diag: one-time summary of how many factories the dispatcher
		// even SAW this tick. If this stays at 0 forever it means the user
		// placed no Factory buildings — flag it so the operator knows the
		// "ingredients aren't moving" symptom is "no consumer" rather than
		// "broken dispatcher".
		long sumNow = factoriesSeenAccumulator.addAndGet(factoriesSeen);
		long withInputsNow = factoriesWithInputsAccumulator.addAndGet(factoriesWithInputs);
		long ticks = restockTicksObserved.incrementAndGet();
		if (ticks == 1L || ticks % 200L == 0L) {
			log.info("[restock/loop-summary] tick #{}: this-tick factories seen={}, with-inputs={}; "
					+ "running totals factories-seen={}, with-inputs={}, attempts={}, "
					+ "no-producer={}, recipe-missing={}, recipe-no-inputs={}",
				ticks, factoriesSeen, factoriesWithInputs,
				sumNow, withInputsNow,
				restockAttempts.get(), restockNoProducerSamples.get(),
				restockRecipeMissing.get(), restockRecipeNoInputs.get());
		}

		maybeReportDispatcherSummary();
		return spawned;
	}

	/** Phase-21: total count of dispatch attempts that resolved to {@link
	 * RoutePlan.NoPath} since boot. Visible for ops + integration-test
	 * inspection; mirrors the counter logged in the periodic dispatcher
	 * summary. */
	public long failedDispatches() {
		return failedDispatches.get();
	}

	/**
	 * Phase-21 unified dispatch entry-point. Asks
	 * {@link RoutePlanner#plan(LatLon, LatLon)} for an explicit
	 * {@link RoutePlan} and pattern-matches the outcome:
	 * <ol>
	 *   <li>{@link RoutePlan.Transit} → spawn a first-mile robot via
	 *       {@link GameState#spawnTransitFirstLeg} carrying a
	 *       {@link TransitBoarding}; the boarding state machine in
	 *       {@code GameState.advanceVehicles} drains it onto an
	 *       ambient transit run.</li>
	 *   <li>{@link RoutePlan.DirectRobot} → spawn directly via
	 *       {@link GameState#spawnRobotWithPath} using the planner's
	 *       precomputed OSM path.</li>
	 *   <li>{@link RoutePlan.NoPath} → increment {@link #failedDispatches}
	 *       and return without debiting the producer.</li>
	 * </ol>
	 *
	 * <p>Producer stock is only ever debited inside the
	 * {@code GameState} spawn helpers; a {@code NoPath} outcome
	 * therefore preserves stock for the next tick.
	 */
	private void dispatchShipment(
		ObjectList<VehicleEvent.Spawned> spawned,
		Building source,
		Building destination,
		String cargoIngredient,
		int quantity,
		@Nullable UUID orderId,
		@Nullable Long spoilageDeadline,
		double straightLineMetres,
		ShipmentKind kind
	) {
		multiLegAttempts.incrementAndGet();
		RoutePlan plan = routePlanner.plan(
			new LatLon(source.lat(), source.lon()),
			new LatLon(destination.lat(), destination.lon()));
		switch (plan) {
			case RoutePlan.Transit transit -> {
				TransitBoarding boarding = new TransitBoarding(
					transit.routeId(),
					transit.boardingStopId(),
					transit.alightingStopId(),
					transit.postTransitPath(),
					transit.postTransitDurationGameMinutes());
				Optional<VehicleEvent.Spawned> result = gameState.spawnTransitFirstLeg(
					source.id(), destination.id(), cargoIngredient, quantity,
					transit.firstLegPath(), transit.firstLegDurationGameMinutes(),
					boarding, orderId, spoilageDeadline);
				if (result.isPresent()) {
					spawned.add(result.get());
					long ok = multiLegSpawned.incrementAndGet();
					if (ok == 1L || ok % 10L == 0L) {
						log.info("[transit/{}/spawned {}] {} m straight-line, "
								+ "first-leg {} game-min → board {} on route {} → alight {}",
							kind.tag, ok, (long) straightLineMetres,
							transit.firstLegDurationGameMinutes(),
							transit.boardingStopId(), transit.routeId(),
							transit.alightingStopId());
					}
				} else {
					multiLegSpawnEmpty.incrementAndGet();
				}
			}
			case RoutePlan.DirectRobot direct -> {
				Optional<VehicleEvent.Spawned> result = gameState.spawnRobotWithPath(
					source.id(), destination.id(), cargoIngredient, quantity,
					direct.path(), direct.durationGameMinutes(),
					orderId, spoilageDeadline);
				if (result.isPresent()) {
					spawned.add(result.get());
					robotShortLegSpawns.incrementAndGet();
				} else {
					long n = restockSpawnEmpty.incrementAndGet();
					if (n <= 5 || n % 25L == 0L) {
						log.info("[direct-robot/{}/spawn-empty {}] direct robot from {} -> {} "
								+ "({} units of {}) rejected by GameState — likely stock race "
								+ "(planner had OK'd the OSM path)",
							kind.tag, n, source.id(), destination.id(),
							quantity, cargoIngredient);
					}
					robotShortLegEmpty.incrementAndGet();
				}
			}
			case RoutePlan.NoPath ignored -> {
				// Phase-21: no transit chain AND no usable direct-robot path.
				// Stock stays on the producer; counter is exposed in the
				// dispatcher summary. Per-tuple wall-clock-throttled log so
				// chronic stalls surface at most once per minute per
				// (producer, consumer, ingredient) triple instead of once
				// per attempt.
				long fails = failedDispatches.incrementAndGet();
				multiLegPlanEmpty.incrementAndGet();
				NoPathKey key = new NoPathKey(source.id(), destination.id(), cargoIngredient);
				long nowWallMs = System.currentTimeMillis();
				// Phase-21 close-out: replaced the previous boolean[]-by-side-effect
				// + re-read pattern (which was racy: two concurrent attempts on the
				// same key could both observe `stored == nowWallMs` and both log).
				// {@link ConcurrentHashMap#compute} returns the value atomically;
				// we piggyback the "did we cross the throttle window?" decision on
				// reference identity of the returned timestamp.
				Long updated = noPathLastLogWallMs.compute(key, (k, prev) -> {
					if (prev == null || nowWallMs - prev >= NO_PATH_LOG_INTERVAL_MS) {
						return nowWallMs;
					}
					return prev;
				});
				boolean shouldLog = updated != null && updated == nowWallMs;
				if (shouldLog) {
					log.info("[no-path/{} #{}] {} m straight-line, no transit chain — "
							+ "producer keeps stock, dispatcher will retry "
							+ "(leg: {} -> {} ({}); total failed dispatches so far: {})",
						kind.tag, fails, (long) straightLineMetres,
						source.id(), destination.id(), cargoIngredient,
						fails);
				}
				if (noPathLastLogWallMs.size() > 1024) {
					noPathLastLogWallMs.entrySet().removeIf(
						e -> nowWallMs - e.getValue() > NO_PATH_LOG_INTERVAL_MS);
				}
			}
		}
	}

	/** Phase-21 dispatch context — disambiguates log lines + counters between
	 * restaurant orders and factory restocks, the only two callers of
	 * {@link #dispatchShipment}. */
	private enum ShipmentKind {
		ORDER("order"), RESTOCK("restock");
		final String tag;
		ShipmentKind(String tag) { this.tag = tag; }
	}


	private void maybeReportDispatcherSummary() {
		long total = multiLegAttempts.get() + robotShortLegSpawns.get() + robotShortLegEmpty.get();
		long lastReported = lastReportedAttempts.get();
		if (total - lastReported < DISPATCHER_REPORT_INTERVAL) {
			return;
		}
		if (!lastReportedAttempts.compareAndSet(lastReported, total)) {
			return;
		}
		log.info("Dispatcher: {} short-leg robots spawned ({} skipped), "
			+ "{} multi-leg attempts → {} chains spawned ({} no-plan, {} spawn-empty); "
			+ "{} no-path failures (stock retained)",
			robotShortLegSpawns.get(),
			robotShortLegEmpty.get(),
			multiLegAttempts.get(),
			multiLegSpawned.get(),
			multiLegPlanEmpty.get(),
			multiLegSpawnEmpty.get(),
			failedDispatches.get());
	}

	private static @Nullable Building findById(List<Building> buildings, UUID id) {
		for (Building building : buildings) {
			if (building.id().equals(id)) {
				return building;
			}
		}
		return null;
	}

	/**
	 * Pick the nearest farm/factory that can supply the order. Prefers an exact recipe
	 * match; falls back to any upstream producer whose output is consumed (transitively)
	 * by the order's recipe. Source must have {@code producedUnits ≥ ROBOT_CARGO_BATCH_SIZE}
	 * to be eligible. Phase-17: distance-branching is now done by the caller, so this
	 * helper always considers candidates of any distance — the dispatcher decides
	 * direct-robot vs. multi-leg based on the chosen producer's actual range.
	 */
	@SuppressWarnings("ConstantValue") // fastutil get() may return null; see dispatch().
	private @Nullable Building pickNearestSourceForOrder(
		List<Building> buildings,
		Order order,
		Building restaurant
	) {
		Building exact = nearestMatching(buildings, restaurant,
			candidate -> hasStock(candidate)
				&& candidate.recipeId().equals(order.recipeId()));
		if (exact != null) {
			return exact;
		}
		ObjectSet<String> closure = transitiveInputClosure(order.recipeId());
		if (closure.isEmpty()) {
			return null;
		}
		return nearestMatching(buildings, restaurant, candidate -> {
			if (!hasStock(candidate)) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient output : recipe.outputs()) {
				if (closure.contains(output.ingredientId())) {
					return true;
				}
			}
			return false;
		});
	}

	@SuppressWarnings("ConstantValue") // fastutil get() may return null; see dispatch().
	private @Nullable Building nearestProducerOf(
		List<Building> buildings,
		Building destination,
		String ingredientId
	) {
		return nearestMatching(buildings, destination, candidate -> {
			// Phase-19: factory restocks accept any source with ≥ 1 unit on hand and
			// ship a partial batch. The full-batch rule still applies to restaurant
			// orders (see {@link #hasStock} / {@link #pickNearestSourceForOrder}).
			if (producerStock(candidate) <= 0 || candidate.id().equals(destination.id())) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient output : recipe.outputs()) {
				if (output.ingredientId().equals(ingredientId)) {
					return true;
				}
			}
			return false;
		});
	}

	private static @Nullable Building nearestMatching(
		List<Building> buildings,
		Building destination,
		Predicate<Building> predicate
	) {
		Building best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Building candidate : buildings) {
			if (candidate instanceof Restaurant) {
				continue;
			}
			if (!predicate.test(candidate)) {
				continue;
			}
			double distance = GameState.haversineMetres(
				candidate.lat(), candidate.lon(), destination.lat(), destination.lon());
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean hasStock(Building candidate) {
		// Phase-17: a candidate is only eligible if it can fill a full robot batch.
		// One-off units stay on the producer until enough accumulate to ship a truck.
		if (candidate instanceof Farm farm) {
			return farm.producedUnits() >= GameConstants.ROBOT_CARGO_BATCH_SIZE;
		}
		if (candidate instanceof Factory factory) {
			return factory.producedUnits() >= GameConstants.ROBOT_CARGO_BATCH_SIZE;
		}
		return false;
	}

	/** Phase-19: how many finished units a farm/factory currently holds.
	 * Used by the restock branch (which tolerates partial batches) where the
	 * full-batch {@link #hasStock} predicate would block the very first
	 * restock until the producer slowly accumulated 5 units. */
	private static int producerStock(Building candidate) {
		if (candidate instanceof Farm farm) {
			return (int) Math.min(Integer.MAX_VALUE, farm.producedUnits());
		}
		if (candidate instanceof Factory factory) {
			return (int) Math.min(Integer.MAX_VALUE, factory.producedUnits());
		}
		return 0;
	}

	/**
	 * Memoised: every ingredient id transitively consumed when producing
	 * {@code rootRecipeId}. Walks the recipe graph breadth-first through producing
	 * recipes (pulled from the cached {@link #recipesByOutput}). Recipe content is
	 * immutable post-load so the result is cached forever.
	 */
	@SuppressWarnings("ConstantValue") // fastutil get() may return null.
	private ObjectSet<String> transitiveInputClosure(String rootRecipeId) {
		ObjectSet<String> cached = transitiveInputClosureByRecipe.get(rootRecipeId);
		if (cached != null) {
			return cached;
		}
		ObjectSet<String> ingredients = new ObjectOpenHashSet<>();
		ObjectSet<String> visitedRecipes = new ObjectOpenHashSet<>();
		ObjectList<String> queue = new ObjectArrayList<>();
		queue.add(rootRecipeId);
		while (!queue.isEmpty()) {
			String recipeId = queue.removeLast();
			if (!visitedRecipes.add(recipeId)) {
				continue;
			}
			Recipe recipe = recipesById.get(recipeId);
			if (recipe == null) {
				continue;
			}
			for (RecipeIngredient input : recipe.inputs()) {
				if (!ingredients.add(input.ingredientId())) {
					continue;
				}
				ObjectList<Recipe> producers = recipesByOutput.get(input.ingredientId());
				if (producers == null) {
					continue;
				}
				for (Recipe producer : producers) {
					queue.add(producer.id());
				}
			}
		}
		transitiveInputClosureByRecipe.put(rootRecipeId, ingredients);
		return ingredients;
	}

	/**
	 * Spoilage deadline = spawn time + shortest {@code shelfLifeMinutes} across the
	 * source recipe's outputs. Refrigerated factories disable spoilage entirely
	 * (Cold Chain milestone). Returns null for non-perishable cargo.
	 */
	private @Nullable Long computeSpoilageDeadline(Building source, @Nullable Recipe recipe) {
		if (source instanceof Factory factory && factory.refrigerated()) {
			return null;
		}
		if (recipe == null) {
			return null;
		}
		long shortest = Long.MAX_VALUE;
		for (RecipeIngredient output : recipe.outputs()) {
			Optional<Ingredient> ingredient = registry.findIngredient(output.ingredientId());
			if (ingredient.isEmpty()) {
				continue;
			}
			long shelf = ingredient.get().shelfLifeMinutes();
			if (shelf > 0 && shelf < shortest) {
				shortest = shelf;
			}
		}
		if (shortest == Long.MAX_VALUE) {
			return null;
		}
		long now = gameState.getClockSnapshot().gameMinutes();
		return now + shortest;
	}
}

