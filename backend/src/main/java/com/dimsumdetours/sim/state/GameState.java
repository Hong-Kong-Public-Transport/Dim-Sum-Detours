package com.dimsumdetours.sim.state;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.GameClock;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderResult;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import com.dimsumdetours.sim.model.vehicle.CargoEvent;
import com.dimsumdetours.sim.model.vehicle.CargoManifest;
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.TransitBoarding;
import com.dimsumdetours.sim.model.vehicle.TransitRunId;
import com.dimsumdetours.sim.model.vehicle.TransitVehicle;
import com.dimsumdetours.sim.model.vehicle.Vehicle;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.model.vehicle.WaitingCargo;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authoritative in-memory game state. Framework-agnostic (no Spring imports
 * — see {@code sim/} package contract). Thread-safe via a single
 * {@link ReentrantLock}.
 *
 * <p>Phase-21 redesign: the recursive {@code VehicleHandoff} chain that
 * spliced wait-robots and buses into {@link #vehicles} has been replaced
 * by an explicit three-step boarding state machine in
 * {@link #advanceVehicles(long)}. The simulation now models transit as
 * server-side bookkeeping ({@link WaitingCargo} queues +
 * {@link TransitVehicle} run handles) rather than as long-lived
 * {@link Vehicle} entries — ambient bus sprites are entirely client-side,
 * the server only allocates per-run state when at least one cargo manifest
 * boards a specific {@link TransitRunId}.
 */
public final class GameState {

	private static final Logger log = LoggerFactory.getLogger(GameState.class);

	private final ContentRegistry registry;
	private final ReentrantLock lock = new ReentrantLock();

	private final Object2ObjectMap<UUID, Building> buildings = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2ObjectMap<UUID, RestaurantOrderQueue> orderQueues = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2ObjectMap<UUID, Vehicle> vehicles = new Object2ObjectLinkedOpenHashMap<>();
	private final ObjectSet<String> activeRestockKeys = new ObjectOpenHashSet<>();
	private final ObjectList<Building> productionTickBuffer = new ObjectArrayList<>();
	private final ObjectList<Vehicle> vehicleTickBuffer = new ObjectArrayList<>();

	/**
	 * Phase-21: cargo waiting at boarding stops, keyed by
	 * {@code (boardingStopId, routeId)}. Populated by the arrivals scan
	 * inside {@link #advanceVehicles(long)} when a first-mile robot
	 * (one with non-null {@link Robot#boarding()}) arrives. Drained by
	 * the boarding scan when the {@link TransitSchedule} reports an
	 * ambient run crossing the stop in the current tick window.
	 */
	private final Object2ObjectMap<WaitingCargoKey, ObjectList<WaitingCargo>> waitingCargo =
		new Object2ObjectLinkedOpenHashMap<>();

	/**
	 * Phase-21: live {@link TransitVehicle}s carrying cargo, keyed by
	 * run id. Created by the boarding scan when the first manifest
	 * boards a previously-empty run (emits {@link CargoEvent.RunStarted});
	 * grown / shrunk by subsequent boardings + alightings; deleted
	 * (and {@link CargoEvent.RunFinished} emitted) when the last
	 * manifest unloads.
	 */
	private final Object2ObjectMap<TransitRunId, TransitVehicle> transitVehicles =
		new Object2ObjectLinkedOpenHashMap<>();

	/** Phase-21: stable composite key for {@link #waitingCargo}. */
	public record WaitingCargoKey(String boardingStopId, String routeId) {
		public WaitingCargoKey {
			if (boardingStopId == null || boardingStopId.isBlank()
				|| routeId == null || routeId.isBlank()) {
				throw new IllegalArgumentException(
					"WaitingCargoKey boardingStopId/routeId must be non-blank");
			}
		}
	}

	private Money balance;
	private final GameClock clock = new GameClock();
	private long lastUpkeepDay = Long.MIN_VALUE;
	private long version = 0L;
	private long worldEpoch = 0L;
	private @Nullable Long pausedSinceGameMinutes = 0L;

	private volatile RouteProvider routeProvider = RouteProvider.straightLine();
	private volatile TransitSchedule transitSchedule = TransitSchedule.disabled();

	/**
	 * Phase-14: install a custom route provider (typically the Spring-wired OSM router).
	 * Pass {@code null} to revert to the straight-line default — useful for tests.
	 */
	public void setRouteProvider(@Nullable RouteProvider provider) {
		this.routeProvider = provider != null ? provider : RouteProvider.straightLine();
	}

	/**
	 * Phase-21: install a {@link TransitSchedule} so the boarding state
	 * machine can pair waiting cargo with ambient transit runs. Defaults
	 * to {@link TransitSchedule#disabled()} so the framework-free core
	 * stays Spring-free; the engine layer wires in
	 * {@code SnapshotTransitSchedule} at boot.
	 */
	public void setTransitSchedule(@Nullable TransitSchedule schedule) {
		this.transitSchedule = schedule != null ? schedule : TransitSchedule.disabled();
	}

	public GameState(ContentRegistry registry) {
		this(registry, Money.of(GameConstants.STARTING_BALANCE));
	}

	public GameState(ContentRegistry registry, Money startingBalance) {
		this.registry = registry;
		this.balance = startingBalance;
	}

	public Money getBalance() {
		lock.lock();
		try {
			return balance;
		} finally {
			lock.unlock();
		}
	}

	public List<Building> listBuildings() {
		lock.lock();
		try {
			return new ArrayList<>(buildings.values());
		} finally {
			lock.unlock();
		}
	}

	public long getBuildingsVersion() {
		lock.lock();
		try {
			return version;
		} finally {
			lock.unlock();
		}
	}

	public PlacementResult placeBuilding(BuildingKind kind, double lat, double lon, String recipeId) {
		return placeBuilding(kind, lat, lon, recipeId, null);
	}

	public PlacementResult placeBuilding(
		BuildingKind kind, double lat, double lon, String recipeId, @Nullable String templateId
	) {
		if (!Double.isFinite(lat) || !Double.isFinite(lon)
			|| lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
			return new PlacementResult.Failure(PlacementError.INVALID_COORDINATES);
		}

		Optional<Recipe> maybeRecipe = registry.findRecipe(recipeId);
		if (maybeRecipe.isEmpty()) {
			return new PlacementResult.Failure(PlacementError.UNKNOWN_RECIPE);
		}
		Recipe recipe = maybeRecipe.get();

		if (kind == BuildingKind.FACTORY && recipe.minimumFactoryTier() > 1) {
			return new PlacementResult.Failure(PlacementError.RECIPE_TIER_TOO_HIGH);
		}
		if (recipe.outputs().isEmpty()) {
			return new PlacementResult.Failure(PlacementError.RECIPE_HAS_NO_OUTPUTS);
		}

		Money cost = kind.buildCost();

		lock.lock();
		try {
			if (!balance.isAtLeast(cost)) {
				return new PlacementResult.Failure(PlacementError.INSUFFICIENT_FUNDS);
			}
			for (Building existing : buildings.values()) {
				if (existing.kind() != kind) {
					continue;
				}
				if (haversineMetres(existing.lat(), existing.lon(), lat, lon)
					< GameConstants.MIN_BUILDING_SPACING_METERS) {
					return new PlacementResult.Failure(PlacementError.TOO_CLOSE_TO_EXISTING_BUILDING);
				}
			}
			Building building = switch (kind) {
				case FARM -> {
					RecipeIngredient firstOutput = recipe.outputs().get(0);
					yield Farm.of(UUID.randomUUID(), lat, lon, recipeId, firstOutput.ingredientId(),
						clock.getGameMinutes(), cycleDurationFor(kind, recipe));
				}
				case FACTORY -> Factory.of(UUID.randomUUID(), lat, lon, recipe,
					clock.getGameMinutes(), cycleDurationFor(kind, recipe));
				case RESTAURANT -> Restaurant.of(UUID.randomUUID(), lat, lon, recipeId, templateId);
			};
			balance = balance.minus(cost);
			buildings.put(building.id(), building);
			version++;
			return new PlacementResult.Success(building, balance);
		} finally {
			lock.unlock();
		}
	}

	public Optional<Building> demolishBuilding(UUID id) {
		lock.lock();
		try {
			Building removed = buildings.remove(id);
			if (removed != null) {
				version++;
			}
			return Optional.ofNullable(removed);
		} finally {
			lock.unlock();
		}
	}

	public Optional<Factory> reorderFactoryOperations(UUID id, List<String> newOperations) {
		lock.lock();
		try {
			Building existing = buildings.get(id);
			if (!(existing instanceof Factory factory)) {
				return Optional.empty();
			}
			Factory updated = factory.withOperations(newOperations);
			buildings.put(id, updated);
			return Optional.of(updated);
		} finally {
			lock.unlock();
		}
	}

	public void reset() {
		lock.lock();
		try {
			buildings.clear();
			orderQueues.clear();
			vehicles.clear();
			activeRestockKeys.clear();
			waitingCargo.clear();
			transitVehicles.clear();
			balance = Money.of(GameConstants.STARTING_BALANCE);
			clock.reset();
			lastUpkeepDay = Long.MIN_VALUE;
			version++;
			worldEpoch++;
			pausedSinceGameMinutes = 0L;
		} finally {
			lock.unlock();
		}
	}

	// ─── Phase 6: restaurant orders ───────────────────────────────────────

	public Optional<Order> enqueueOrder(UUID restaurantId, String recipeId, int quantity, long patienceGameMinutes) {
		lock.lock();
		try {
			Building existing = buildings.get(restaurantId);
			if (!(existing instanceof Restaurant)) {
				return Optional.empty();
			}
			long now = clock.getGameMinutes();
			Order order = new Order(
				UUID.randomUUID(),
				restaurantId,
				recipeId,
				quantity,
				now,
				now + patienceGameMinutes);
			orderQueues.computeIfAbsent(restaurantId, id -> new RestaurantOrderQueue()).enqueue(order);
			return Optional.of(order);
		} finally {
			lock.unlock();
		}
	}

	public List<Order> listOrders(UUID restaurantId) {
		lock.lock();
		try {
			RestaurantOrderQueue queue = orderQueues.get(restaurantId);
			return queue == null ? List.of() : queue.snapshot();
		} finally {
			lock.unlock();
		}
	}

	public List<Order> listAllOrders() {
		lock.lock();
		try {
			List<Order> all = new ArrayList<>();
			for (RestaurantOrderQueue queue : orderQueues.values()) {
				all.addAll(queue.snapshot());
			}
			return all;
		} finally {
			lock.unlock();
		}
	}

	public Optional<OrderOutcome> fulfillOrder(UUID restaurantId, UUID orderId) {
		lock.lock();
		try {
			RestaurantOrderQueue queue = orderQueues.get(restaurantId);
			if (queue == null) {
				return Optional.empty();
			}
			Building building = buildings.get(restaurantId);
			if (!(building instanceof Restaurant restaurant)) {
				return Optional.empty();
			}
			OrderResult result = queue.fulfill(orderId, clock.getGameMinutes());
			if (result == null) {
				return Optional.empty();
			}
			long basePayout = lookupBasePayout(restaurant);
			long payout = (result == OrderResult.LATE)
				? Math.round(basePayout * GameConstants.LATE_DELIVERY_PAYOUT_MULTIPLIER)
				: basePayout;
			balance = balance.plus(Money.of(payout));
			double delta = (result == OrderResult.FULFILLED)
				? GameConstants.REPUTATION_GAIN_ON_TIME
				: -GameConstants.REPUTATION_LOSS_LATE;
			Restaurant updated = restaurant.withReputation(restaurant.reputation() + delta)
				.withFulfilledIncremented();
			buildings.put(restaurantId, updated);
			return Optional.of(new OrderOutcome(result, payout, balance.amount(), updated.reputation()));
		} finally {
			lock.unlock();
		}
	}

	public List<ExpiredOrderEvent> expirePendingOrders() {
		lock.lock();
		try {
			long now = clock.getGameMinutes();
			List<ExpiredOrderEvent> events = new ArrayList<>();
			for (RestaurantOrderQueue queue : orderQueues.values()) {
				for (Order expired : queue.expireUpTo(now)) {
					Building building = buildings.get(expired.restaurantId());
					double newReputation = 0.0;
					if (building instanceof Restaurant restaurant) {
						Restaurant updated = restaurant.withReputation(
							restaurant.reputation() - GameConstants.REPUTATION_LOSS_MISSED);
						buildings.put(updated.id(), updated);
						newReputation = updated.reputation();
					}
					events.add(new ExpiredOrderEvent(expired, newReputation));
				}
			}
			return events;
		} finally {
			lock.unlock();
		}
	}

	public void advanceProduction() {
		lock.lock();
		try {
			long now = clock.getGameMinutes();
			productionTickBuffer.clear();
			productionTickBuffer.addAll(buildings.values());
			for (Building building : productionTickBuffer) {
				switch (building) {
					case Farm farm -> {
						long elapsed = now - farm.cycleStartedAtGameMinutes();
						if (elapsed < farm.cycleDurationGameMinutes()) {
							continue;
						}
						long completed = elapsed / farm.cycleDurationGameMinutes();
						long newAnchor = farm.cycleStartedAtGameMinutes()
							+ completed * farm.cycleDurationGameMinutes();
						buildings.put(farm.id(), farm.withProducedAdvance(completed, newAnchor));
					}
					case Factory factory -> {
						long elapsed = now - factory.cycleStartedAtGameMinutes();
						if (elapsed < factory.cycleDurationGameMinutes()) {
							continue;
						}
						long elapsedCycles = elapsed / factory.cycleDurationGameMinutes();
						Optional<Recipe> recipeOpt = registry.findRecipe(factory.recipeId());
						long completed;
						Factory afterInputs = factory;
						if (recipeOpt.isPresent() && !recipeOpt.get().inputs().isEmpty()) {
							Recipe recipe = recipeOpt.get();
							long affordable = 0L;
							while (affordable < elapsedCycles && afterInputs.hasInputsFor(recipe)) {
								afterInputs = afterInputs.withInputsConsumed(recipe);
								affordable++;
							}
							completed = affordable;
						} else {
							completed = elapsedCycles;
						}
						if (completed <= 0) {
							if (factory.cycleStartedAtGameMinutes() != now) {
								buildings.put(factory.id(), new Factory(
									factory.id(), factory.lat(), factory.lon(), factory.recipeId(),
									factory.operations(), now,
									factory.cycleDurationGameMinutes(), factory.producedUnits(),
									factory.refrigerated(), factory.inputStockpile()));
							}
							continue;
						}
						long newAnchor = (completed == elapsedCycles)
							? factory.cycleStartedAtGameMinutes()
								+ completed * factory.cycleDurationGameMinutes()
							: now;
						buildings.put(factory.id(),
							afterInputs.withProducedAdvance(completed, newAnchor));
					}
					case Restaurant ignored -> {
						// Restaurants don't produce; deliveries arrive from elsewhere.
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}

	public Optional<Building> tryConsumeProducedUnit(UUID buildingId) {
		lock.lock();
		try {
			Building existing = buildings.get(buildingId);
			if (existing instanceof Farm farm) {
				return farm.withProducedUnitConsumed().map(updated -> {
					buildings.put(updated.id(), updated);
					return (Building) updated;
				});
			}
			if (existing instanceof Factory factory) {
				return factory.withProducedUnitConsumed().map(updated -> {
					buildings.put(updated.id(), updated);
					return (Building) updated;
				});
			}
			return Optional.empty();
		} finally {
			lock.unlock();
		}
	}

	public Optional<Factory> tryDeliverInputToFactory(UUID factoryId, String ingredientId, int quantity) {
		if (quantity <= 0) {
			return Optional.empty();
		}
		lock.lock();
		try {
			Building existing = buildings.get(factoryId);
			if (!(existing instanceof Factory factory)) {
				return Optional.empty();
			}
			Factory updated = factory.withInputDelivered(ingredientId, quantity);
			buildings.put(factoryId, updated);
			return Optional.of(updated);
		} finally {
			lock.unlock();
		}
	}

	public Optional<Factory> tryUpgradeFactoryRefrigeration(UUID buildingId) {
		lock.lock();
		try {
			Building existing = buildings.get(buildingId);
			if (!(existing instanceof Factory factory)) {
				return Optional.empty();
			}
			if (factory.refrigerated()) {
				return Optional.of(factory);
			}
			Money cost = Money.of(GameConstants.REFRIGERATION_UPGRADE_COST);
			if (!balance.isAtLeast(cost)) {
				throw new IllegalStateException("INSUFFICIENT_FUNDS");
			}
			balance = balance.minus(cost);
			Factory upgraded = factory.withRefrigerated();
			buildings.put(upgraded.id(), upgraded);
			return Optional.of(upgraded);
		} finally {
			lock.unlock();
		}
	}

	private static long cycleDurationFor(BuildingKind kind, Recipe recipe) {
		long base = Math.max(1L, recipe.operationDurationMinutes());
		return switch (kind) {
			case FARM -> base;
			case FACTORY -> base * Math.max(1, recipe.operations().size());
			case RESTAURANT -> throw new IllegalArgumentException(
				"Restaurants don't have a production cycle; cycleDurationFor() not callable");
		};
	}

	private long lookupBasePayout(Restaurant restaurant) {
		String templateId = restaurant.templateId();
		if (templateId == null) {
			return GameConstants.DEFAULT_RESTAURANT_PAYOUT;
		}
		return registry.findRestaurantTemplate(templateId)
			.map(RestaurantTemplate::basePayout)
			.orElse(GameConstants.DEFAULT_RESTAURANT_PAYOUT);
	}

	public Optional<OrderOutcome> spoilOrder(UUID restaurantId, UUID orderId) {
		lock.lock();
		try {
			RestaurantOrderQueue queue = orderQueues.get(restaurantId);
			if (queue == null) {
				return Optional.empty();
			}
			OrderResult removed = queue.fulfill(orderId, clock.getGameMinutes());
			if (removed == null) {
				return Optional.empty();
			}
			Building building = buildings.get(restaurantId);
			double newReputation = 0.0;
			if (building instanceof Restaurant restaurant) {
				Restaurant updated = restaurant.withReputation(
					restaurant.reputation() - GameConstants.REPUTATION_LOSS_MISSED);
				buildings.put(restaurantId, updated);
				newReputation = updated.reputation();
			}
			return Optional.of(new OrderOutcome(OrderResult.SPOILED, 0L, balance.amount(), newReputation));
		} finally {
			lock.unlock();
		}
	}

	public long applyDailyUpkeepIfDayChanged() {
		lock.lock();
		try {
			long currentDay = clock.getGameMinutes() / (24L * 60L);
			if (lastUpkeepDay == Long.MIN_VALUE) {
				lastUpkeepDay = currentDay;
				return 0L;
			}
			if (currentDay <= lastUpkeepDay) {
				return 0L;
			}
			long daysElapsed = currentDay - lastUpkeepDay;
			lastUpkeepDay = currentDay;
			long total = 0L;
			for (Building building : buildings.values()) {
				total += building.kind().dailyUpkeep().amount() * daysElapsed;
			}
			long deducted = Math.min(balance.amount(), total);
			balance = Money.of(balance.amount() - deducted);
			return deducted;
		} finally {
			lock.unlock();
		}
	}

	public record OrderOutcome(OrderResult result, long payout, long newBalance, double newReputation) {
	}

	public record ExpiredOrderEvent(Order order, double newReputation) {
	}

	// ─── Phase 12: vehicles (robots) ──────────────────────────────────────

	public List<Vehicle> listVehicles() {
		lock.lock();
		try {
			return new ArrayList<>(vehicles.values());
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-12: spawn a robot carrying one ingredient from a producer to a
	 * downstream factory or restaurant. Atomically debits one finished
	 * unit from the source's stockpile (or returns empty when the source
	 * is dry / unknown / not a producer). Path is computed by the
	 * {@link RouteProvider}; if no path exists or the routed length
	 * exceeds {@link GameConstants#MAX_ROBOT_LEG_METERS}, returns empty
	 * without debiting stock.
	 */
	public Optional<VehicleEvent.Spawned> spawnRobot(
		UUID sourceId,
		UUID destinationId,
		String ingredientId,
		int quantity,
		@Nullable UUID orderId,
		@Nullable Long spoilageDeadline
	) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, got " + quantity);
		}
		lock.lock();
		try {
			Building source = buildings.get(sourceId);
			Building destination = buildings.get(destinationId);
			if (source == null || destination == null) {
				return Optional.empty();
			}
			double straightLineMeters = haversineMetres(
				source.lat(), source.lon(), destination.lat(), destination.lon());
			if (straightLineMeters > GameConstants.MAX_ROBOT_LEG_METERS) {
				return Optional.empty();
			}
			List<LatLon> path = routeProvider.findPath(
				new LatLon(source.lat(), source.lon()),
				new LatLon(destination.lat(), destination.lon()));
			if (path == null || path.size() < 2) {
				return Optional.empty();
			}
			Optional<Building> debited = consumeProducedUnitsInternal(source, quantity);
			if (debited.isEmpty()) {
				return Optional.empty();
			}
			buildings.put(sourceId, debited.get());

			long now = clock.getGameMinutes();
			long departsAt = now + GameConstants.ROBOT_LOADING_GAME_MINUTES;
			double distanceMeters = pathLengthMetres(path);
			long travel = Math.max(1L, Math.round(
				distanceMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));
			Robot robot = new Robot(
				UUID.randomUUID(),
				sourceId,
				destinationId,
				Map.of(ingredientId, quantity),
				path,
				now,
				departsAt,
				departsAt + travel,
				orderId,
				spoilageDeadline,
				null);
			vehicles.put(robot.id(), robot);
			if (orderId == null) {
				activeRestockKeys.add(restockKey(sourceId, destinationId, ingredientId));
			}
			return Optional.of(new VehicleEvent.Spawned(robot, now));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-21: spawn a direct-delivery robot using a planner-supplied
	 * path (skips the OSM re-routing inside {@link #spawnRobot} because
	 * {@link com.dimsumdetours.engine.routing.RoutePlanner} has already
	 * computed it). Bypasses the haversine cap check (the planner
	 * already enforced it). Same atomic debit semantics as
	 * {@link #spawnRobot}.
	 */
	public Optional<VehicleEvent.Spawned> spawnRobotWithPath(
		UUID sourceId,
		UUID destinationId,
		String ingredientId,
		int quantity,
		List<LatLon> path,
		long durationGameMinutes,
		@Nullable UUID orderId,
		@Nullable Long spoilageDeadline
	) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, got " + quantity);
		}
		if (path.size() < 2) {
			throw new IllegalArgumentException("path must have ≥ 2 waypoints, got " + path.size());
		}
		if (durationGameMinutes < 0L) {
			throw new IllegalArgumentException(
				"durationGameMinutes must be non-negative, got " + durationGameMinutes);
		}
		lock.lock();
		try {
			Building source = buildings.get(sourceId);
			Building destination = buildings.get(destinationId);
			if (source == null || destination == null) {
				return Optional.empty();
			}
			Optional<Building> debited = consumeProducedUnitsInternal(source, quantity);
			if (debited.isEmpty()) {
				return Optional.empty();
			}
			buildings.put(sourceId, debited.get());

			long now = clock.getGameMinutes();
			long departsAt = now + GameConstants.ROBOT_LOADING_GAME_MINUTES;
			Robot robot = new Robot(
				UUID.randomUUID(),
				sourceId,
				destinationId,
				Map.of(ingredientId, quantity),
				path,
				now,
				departsAt,
				departsAt + Math.max(1L, durationGameMinutes),
				orderId,
				spoilageDeadline,
				null);
			vehicles.put(robot.id(), robot);
			if (orderId == null) {
				activeRestockKeys.add(restockKey(sourceId, destinationId, ingredientId));
			}
			return Optional.of(new VehicleEvent.Spawned(robot, now));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-21: spawn the first-mile robot of a transit plan. The robot
	 * carries a {@link TransitBoarding} so the arrivals scan in
	 * {@link #advanceVehicles(long)} routes its cargo into the
	 * {@link #waitingCargo} queue keyed by
	 * {@code (boardingStopId, routeId)} instead of applying it to the
	 * destination directly. The boarding scan in the same (or a later)
	 * tick attaches the cargo to whichever ambient run crosses the stop.
	 *
	 * <p>Atomically debits the source's stock; restock dedup is set on
	 * the {@code (source, finalDest, ingredient)} tuple here and only
	 * cleared on terminal (connecting-robot) arrival.
	 */
	public Optional<VehicleEvent.Spawned> spawnTransitFirstLeg(
		UUID sourceId,
		UUID finalDestinationId,
		String ingredientId,
		int quantity,
		List<LatLon> firstLegPath,
		long firstLegDurationGameMinutes,
		TransitBoarding boarding,
		@Nullable UUID orderId,
		@Nullable Long spoilageDeadline
	) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, got " + quantity);
		}
		if (firstLegPath.size() < 2) {
			throw new IllegalArgumentException(
				"firstLegPath must have ≥ 2 waypoints, got " + firstLegPath.size());
		}
		if (firstLegDurationGameMinutes < 0L) {
			throw new IllegalArgumentException(
				"firstLegDurationGameMinutes must be non-negative, got "
					+ firstLegDurationGameMinutes);
		}
		lock.lock();
		try {
			Building source = buildings.get(sourceId);
			Building destination = buildings.get(finalDestinationId);
			if (source == null || destination == null) {
				return Optional.empty();
			}
			Optional<Building> debited = consumeProducedUnitsInternal(source, quantity);
			if (debited.isEmpty()) {
				return Optional.empty();
			}
			buildings.put(sourceId, debited.get());

			long now = clock.getGameMinutes();
			long departsAt = now + GameConstants.ROBOT_LOADING_GAME_MINUTES;
			Robot robot = new Robot(
				UUID.randomUUID(),
				sourceId,
				finalDestinationId,
				Map.of(ingredientId, quantity),
				firstLegPath,
				now,
				departsAt,
				departsAt + Math.max(1L, firstLegDurationGameMinutes),
				orderId,
				spoilageDeadline,
				boarding);
			vehicles.put(robot.id(), robot);
			if (orderId == null) {
				activeRestockKeys.add(restockKey(sourceId, finalDestinationId, ingredientId));
			}
			return Optional.of(new VehicleEvent.Spawned(robot, now));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-12: walk every in-flight vehicle and run the Phase-21
	 * three-step boarding state machine over the
	 * {@code (lastTickGameMinutes, now]} interval:
	 * <ol>
	 *   <li><strong>Arrivals scan.</strong> Each vehicle whose
	 *       {@code arrivesAt ≤ now} is removed; if it carried a
	 *       {@link TransitBoarding} the cargo is queued onto the
	 *       {@link #waitingCargo} map and a
	 *       {@link VehicleEvent.RobotArrivedAtStop} is emitted; otherwise
	 *       the cargo is applied to the destination via
	 *       {@link #applyArrival}.</li>
	 *   <li><strong>Boarding scan.</strong> For each non-empty
	 *       {@link WaitingCargoKey}, ask the {@link TransitSchedule} for
	 *       an ambient run on this {@code routeId} crossing
	 *       {@code stopId} inside the tick window. On a hit, drain the
	 *       queue into {@link CargoManifest}s, attach them to a
	 *       {@link TransitVehicle} (creating it + emitting
	 *       {@link CargoEvent.RunStarted} when the run was previously
	 *       empty), and emit {@link CargoEvent.CargoLoaded} per
	 *       manifest.</li>
	 *   <li><strong>Alighting scan.</strong> For each live
	 *       {@link TransitVehicle}, for any manifest whose alighting
	 *       stop arrival ∈ tick window, remove the manifest, spawn a
	 *       connecting robot to walk the post-transit path, and emit
	 *       {@link CargoEvent.CargoUnloaded} (plus
	 *       {@link CargoEvent.RunFinished} when the last manifest
	 *       leaves).</li>
	 * </ol>
	 */
	public ArrivalBatch advanceVehicles() {
		return advanceVehicles(clock.getGameMinutes());
	}

	public ArrivalBatch advanceVehicles(long lastTickGameMinutes) {
		lock.lock();
		try {
			long now = clock.getGameMinutes();
			List<VehicleEvent.Arrived> arrived = new ArrayList<>();
			List<VehicleEvent> miscEvents = new ArrayList<>();
			List<VehicleEvent.Spawned> spawned = new ArrayList<>();
			List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> fulfilments = new ArrayList<>();
			List<CargoEvent> cargoEvents = new ArrayList<>();

			// 1. Arrivals scan. Drain every vehicle that has reached its arrival
			// deadline; first-mile robots feed WaitingCargo, others apply cargo
			// to their destination as before.
			vehicleTickBuffer.clear();
			vehicleTickBuffer.addAll(vehicles.values());
			for (Vehicle vehicle : vehicleTickBuffer) {
				if (now < vehicle.arrivesAtGameMinutes()) {
					continue;
				}
				vehicles.remove(vehicle.id());

				if (vehicle instanceof Robot robot && robot.boarding() != null) {
					TransitBoarding boarding = robot.boarding();
					CargoManifest manifest = new CargoManifest(
						UUID.randomUUID(),
						robot.sourceBuildingId(),
						robot.destinationBuildingId(),
						boarding.boardingStopId(),
						boarding.alightingStopId(),
						robot.cargo(),
						robot.orderId(),
						robot.spoilageDeadlineGameMinutes(),
						boarding.postTransitPath(),
						boarding.postTransitDurationGameMinutes(),
						now);
					WaitingCargoKey key = new WaitingCargoKey(
						boarding.boardingStopId(), boarding.routeId());
					waitingCargo.computeIfAbsent(key, k -> new ObjectArrayList<>())
						.add(new WaitingCargo(
							UUID.randomUUID(),
							boarding.routeId(),
							boarding.boardingStopId(),
							manifest,
							now));
					miscEvents.add(new VehicleEvent.RobotArrivedAtStop(
						robot.id(),
						boarding.boardingStopId(),
						boarding.routeId(),
						now));
					continue;
				}

				// Final-leg arrival: clear restock dedup, apply cargo to destination.
				if (vehicle.orderId() == null) {
					activeRestockKeys.remove(restockKey(
						vehicle.sourceBuildingId(),
						vehicle.destinationBuildingId(),
						firstCargoIngredient(vehicle)));
				}
				OrderResult orderResult = applyArrival(vehicle, now, fulfilments);
				arrived.add(new VehicleEvent.Arrived(
					vehicle.id(),
					vehicle.destinationBuildingId(),
					vehicle.orderId(),
					orderResult,
					now));
			}

			// 2. Boarding scan. For each non-empty waiting queue, ask the
			// TransitSchedule for an ambient run crossing the boarding stop
			// inside (lastTickNow, now]. We pop the entire queue onto the
			// matching TransitVehicle in one shot: the same boarding window
			// can't host two distinct runs (5-min headway > tick interval),
			// and even if a future schedule does, the next tick's scan will
			// pick up any leftovers.
			List<WaitingCargoKey> drainedKeys = new ArrayList<>();
			for (Map.Entry<WaitingCargoKey, ObjectList<WaitingCargo>> entry : waitingCargo.entrySet()) {
				WaitingCargoKey key = entry.getKey();
				ObjectList<WaitingCargo> queue = entry.getValue();
				if (queue.isEmpty()) {
					drainedKeys.add(key);
					continue;
				}
				TransitSchedule.@Nullable RunArrival hit = transitSchedule.findRunCrossingStop(
					key.routeId(), key.boardingStopId(), lastTickGameMinutes, now);
				if (hit == null) {
					continue;
				}
				TransitRunId runId = new TransitRunId(key.routeId(), hit.departureOffsetGameMinutes());
				TransitVehicle existing = transitVehicles.get(runId);
				boolean firstManifest = (existing == null);
				if (firstManifest) {
					existing = new TransitVehicle(runId, hit.gtfsRouteType(), List.of());
					cargoEvents.add(new CargoEvent.RunStarted(runId, hit.gtfsRouteType(), now));
				}
				for (WaitingCargo waiting : queue) {
					CargoManifest manifest = waiting.manifest();
					existing = existing.withManifestAppended(manifest);
					transitVehicles.put(runId, existing);
					cargoEvents.add(new CargoEvent.CargoLoaded(
						runId, manifest.id(), existing.totalCargoUnits(), now));
				}
				queue.clear();
				drainedKeys.add(key);
			}
			for (WaitingCargoKey k : drainedKeys) {
				ObjectList<WaitingCargo> q = waitingCargo.get(k);
				if (q != null && q.isEmpty()) {
					waitingCargo.remove(k);
				}
			}

			// 3. Alighting scan. For each live transit vehicle, check every
			// manifest's alighting-stop arrival; if it falls in the tick
			// window, remove the manifest from the run, spawn a connecting
			// robot for the post-transit walk, and emit CARGO_UNLOADED
			// (plus RUN_FINISHED when the last manifest leaves).
			List<TransitRunId> runsToInspect = new ArrayList<>(transitVehicles.keySet());
			for (TransitRunId runId : runsToInspect) {
				TransitVehicle live = transitVehicles.get(runId);
				if (live == null) {
					continue;
				}
				List<CargoManifest> remaining = new ArrayList<>(live.manifests());
				List<CargoManifest> alighted = new ArrayList<>();
				java.util.Iterator<CargoManifest> it = remaining.iterator();
				while (it.hasNext()) {
					CargoManifest manifest = it.next();
					OptionalLong alightAt = transitSchedule.arrivalAtStop(
						runId.routeId(), runId.departureOffsetGameMinutes(),
						manifest.alightingStopId());
					if (alightAt.isEmpty()) {
						continue;
					}
					long absolute = alightAt.getAsLong();
					if (absolute > lastTickGameMinutes && absolute <= now) {
						it.remove();
						alighted.add(manifest);
					}
				}
				if (alighted.isEmpty()) {
					continue;
				}
				TransitVehicle updated = live.withManifests(remaining);
				if (updated.isEmpty()) {
					transitVehicles.remove(runId);
				} else {
					transitVehicles.put(runId, updated);
				}
				// Phase-21 close-out: previously every CARGO_UNLOADED frame in
				// a multi-manifest alight reported the same post-all-removal
				// total, so the frontend's sprite scale jumped straight to the
				// final value. Decrement per manifest so each frame carries
				// the true remaining-on-board count.
				int runningTotal = live.totalCargoUnits();
				for (CargoManifest manifest : alighted) {
					int manifestUnits = 0;
					for (Integer qty : manifest.cargo().values()) {
						manifestUnits += qty;
					}
					runningTotal = Math.max(0, runningTotal - manifestUnits);
					cargoEvents.add(new CargoEvent.CargoUnloaded(
						runId, manifest.id(), runningTotal, now));
					Optional<VehicleEvent.Spawned> connecting = spawnConnectingRobotInternal(
						manifest, now);
					connecting.ifPresent(spawned::add);
				}
				if (updated.isEmpty()) {
					cargoEvents.add(new CargoEvent.RunFinished(runId, now));
				}
			}

			// {@link VehicleEvent.RobotArrivedAtStop} entries live in
			// {@link ArrivalBatch#miscEvents()} — the engine fans them onto
			// the vehicle SSE channel verbatim (distinct from the regular
			// {@link VehicleEvent.Arrived} stream because no destination
			// building was credited).
			return new ArrivalBatch(arrived, spawned, fulfilments, cargoEvents, miscEvents);
		} finally {
			lock.unlock();
		}
	}

	/** Spawn the connecting (alighting-side) robot for a manifest. Skips
	 * stock debit (the cargo is already in flight). Loading window is
	 * also zero — the cargo is being handed off from the bus, not
	 * loaded fresh from a producer. */
	private Optional<VehicleEvent.Spawned> spawnConnectingRobotInternal(
		CargoManifest manifest, long now
	) {
		Robot robot = new Robot(
			UUID.randomUUID(),
			manifest.sourceBuildingId(),
			manifest.destinationBuildingId(),
			manifest.cargo(),
			manifest.postTransitPath(),
			now,
			now,
			now + Math.max(1L, manifest.postTransitDurationGameMinutes()),
			manifest.orderId(),
			manifest.spoilageDeadlineGameMinutes(),
			null);
		vehicles.put(robot.id(), robot);
		return Optional.of(new VehicleEvent.Spawned(robot, now));
	}

	/** Bundle returned from {@link #advanceVehicles()}. */
	public record ArrivalBatch(
		List<VehicleEvent.Arrived> vehicleEvents,
		List<VehicleEvent.Spawned> spawnEvents,
		List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> orderEvents,
		List<CargoEvent> cargoEvents,
		List<VehicleEvent> miscEvents
	) {
	}

	/**
	 * Phase-21 cold-boot accessor. Returns a snapshot of every transit
	 * run currently carrying cargo, keyed by {@link TransitRunId} with
	 * the cumulative unit count aboard.
	 */
	public Map<TransitRunId, Integer> cargoTransitRunsSnapshot() {
		lock.lock();
		try {
			Map<TransitRunId, Integer> snapshot = new LinkedHashMap<>();
			for (Map.Entry<TransitRunId, TransitVehicle> entry : transitVehicles.entrySet()) {
				snapshot.put(entry.getKey(), entry.getValue().totalCargoUnits());
			}
			return snapshot;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-21: defensive snapshot of cargo waiting at boarding stops,
	 * summing units per {@link WaitingCargoKey}.
	 */
	public Map<WaitingCargoKey, Integer> waitingCargoSnapshot() {
		lock.lock();
		try {
			Map<WaitingCargoKey, Integer> snapshot = new LinkedHashMap<>();
			for (Map.Entry<WaitingCargoKey, ObjectList<WaitingCargo>> entry : waitingCargo.entrySet()) {
				int units = 0;
				for (WaitingCargo queued : entry.getValue()) {
					units += totalCargoUnits(queued.manifest().cargo());
				}
				if (units > 0) {
					snapshot.put(entry.getKey(), units);
				}
			}
			return snapshot;
		} finally {
			lock.unlock();
		}
	}

	private static int totalCargoUnits(Map<String, Integer> cargo) {
		int total = 0;
		for (Integer qty : cargo.values()) {
			if (qty != null) total += qty;
		}
		return total;
	}

	private @Nullable OrderResult applyArrival(
		Vehicle vehicle,
		long now,
		List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> fulfilments
	) {
		Building destination = buildings.get(vehicle.destinationBuildingId());
		Map.Entry<String, Integer> only = vehicle.cargo().entrySet().iterator().next();
		String ingredientId = only.getKey();
		int quantity = only.getValue();

		if (vehicle.orderId() == null) {
			if (destination instanceof Factory factory) {
				buildings.put(factory.id(), factory.withInputDelivered(ingredientId, quantity));
			}
			return null;
		}

		boolean spoiled = vehicle.spoilageDeadlineGameMinutes() != null
			&& now > vehicle.spoilageDeadlineGameMinutes();
		Optional<OrderOutcome> outcome = spoiled
			? spoilOrder(vehicle.destinationBuildingId(), vehicle.orderId())
			: fulfillOrder(vehicle.destinationBuildingId(), vehicle.orderId());
		outcome.ifPresent(o -> fulfilments.add(new com.dimsumdetours.sim.model.OrderEvent.Fulfilled(
			vehicle.orderId(),
			vehicle.destinationBuildingId(),
			o.result(),
			o.payout(),
			o.newBalance(),
			o.newReputation(),
			now)));
		return outcome.map(OrderOutcome::result).orElse(null);
	}

	public boolean hasActiveRestock(UUID sourceId, UUID destinationId, String ingredientId) {
		lock.lock();
		try {
			return activeRestockKeys.contains(restockKey(sourceId, destinationId, ingredientId));
		} finally {
			lock.unlock();
		}
	}

	public boolean hasInFlightOrder(UUID orderId) {
		lock.lock();
		try {
			for (Vehicle vehicle : vehicles.values()) {
				if (orderId.equals(vehicle.orderId())) {
					return true;
				}
			}
			// Phase-21: cargo riding a transit vehicle isn't in `vehicles`
			// any more — check the live manifests too.
			for (TransitVehicle tv : transitVehicles.values()) {
				for (CargoManifest m : tv.manifests()) {
					if (orderId.equals(m.orderId())) {
						return true;
					}
				}
			}
			// And cargo waiting at a boarding stop.
			for (ObjectList<WaitingCargo> queue : waitingCargo.values()) {
				for (WaitingCargo wc : queue) {
					if (orderId.equals(wc.manifest().orderId())) {
						return true;
					}
				}
			}
			return false;
		} finally {
			lock.unlock();
		}
	}

	private static String restockKey(UUID sourceId, UUID destinationId, String ingredientId) {
		return sourceId + ":" + destinationId + ":" + ingredientId;
	}

	private static String firstCargoIngredient(Vehicle vehicle) {
		return vehicle.cargo().keySet().iterator().next();
	}

	private Optional<Building> consumeProducedUnitsInternal(Building source, int n) {
		if (source instanceof Farm farm) {
			return farm.withProducedUnitsConsumed(n).map(updated -> (Building) updated);
		}
		if (source instanceof Factory factory) {
			return factory.withProducedUnitsConsumed(n).map(updated -> (Building) updated);
		}
		return Optional.empty();
	}

	// ─── Phase 4: clock access ────────────────────────────────────────────

	public ClockSnapshot getClockSnapshot() {
		lock.lock();
		try {
			return new ClockSnapshot(
				clock.getGameMinutes(),
				clock.getDayOfWeek(),
				clock.getMinuteOfDay(),
				clock.getGameDay(),
				clock.getGameWeek(),
				clock.getGameMonth(),
				clock.getGameYear(),
				clock.getSpeedMultiplier(),
				!clock.isPaused(),
				System.currentTimeMillis(),
				clock.isPaused() ? pausedSinceGameMinutes : null,
				worldEpoch);
		} finally {
			lock.unlock();
		}
	}

	public void setClockSpeed(int speed) {
		lock.lock();
		try {
			clock.setSpeedMultiplier(speed);
		} finally {
			lock.unlock();
		}
	}

	public void setClockPaused(boolean paused) {
		lock.lock();
		try {
			boolean wasPaused = clock.isPaused();
			clock.setPaused(paused);
			if (paused && !wasPaused) {
				pausedSinceGameMinutes = clock.getGameMinutes();
			} else if (!paused) {
				pausedSinceGameMinutes = null;
			}
		} finally {
			lock.unlock();
		}
	}

	public long worldEpoch() {
		lock.lock();
		try {
			return worldEpoch;
		} finally {
			lock.unlock();
		}
	}

	public void advanceClock(long deltaGameMinutes) {
		if (deltaGameMinutes <= 0) {
			return;
		}
		lock.lock();
		try {
			clock.advance(deltaGameMinutes);
		} finally {
			lock.unlock();
		}
	}

	public record ClockSnapshot(
		long gameMinutes,
		int dayOfWeek,
		int minuteOfDay,
		long gameDay,
		long gameWeek,
		long gameMonth,
		long gameYear,
		int speed,
		boolean playing,
		long serverWallClockMs,
		@org.jspecify.annotations.Nullable Long pausedSinceGameMinutes,
		long worldEpoch
	) {
	}

	public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
		double earthRadiusMetres = 6_371_000.0;
		double phi1 = Math.toRadians(lat1);
		double phi2 = Math.toRadians(lat2);
		double deltaPhi = Math.toRadians(lat2 - lat1);
		double deltaLambda = Math.toRadians(lon2 - lon1);
		double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
			+ Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return earthRadiusMetres * c;
	}

	public static double pathLengthMetres(List<LatLon> path) {
		double total = 0.0;
		for (int i = 1; i < path.size(); i++) {
			LatLon a = path.get(i - 1);
			LatLon b = path.get(i);
			total += haversineMetres(a.lat(), a.lon(), b.lat(), b.lon());
		}
		return total;
	}
}


