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
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.Vehicle;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authoritative in-memory game state for Phase 3: balance + placed buildings.
 *
 * <p>Framework-agnostic (no Spring imports — see {@code sim/} package contract). Thread-safe
 * via a single {@link ReentrantLock}; all mutating operations decide their reads/writes under
 * the same lock so the controller can call them from any reactive scheduler.
 *
 * <p>Persistence is deliberately out-of-scope here — Phase 6 ("is it fun?") is the right time
 * to add a JPA-backed repo. The state is rebuilt from {@link GameConstants#STARTING_BALANCE}
 * on every backend restart for now.
 */
public final class GameState {

	private final ContentRegistry registry;
	private final ReentrantLock lock = new ReentrantLock();

	// Ordered for predictable list responses; matches the ContentRegistry style.
	private final Object2ObjectMap<UUID, Building> buildings = new Object2ObjectLinkedOpenHashMap<>();
	/** Phase 6: per-restaurant pending-order queues. Lazily allocated on first enqueue. */
	private final Object2ObjectMap<UUID, RestaurantOrderQueue> orderQueues = new Object2ObjectLinkedOpenHashMap<>();
	/**
	 * Phase-12: live in-flight vehicles, keyed by id. Mutated under the same {@link #lock}
	 * as everything else so {@link #spawnRobot} and {@link #advanceVehicles} can debit
	 * producers / credit destinations atomically with their position updates.
	 */
	private final Object2ObjectMap<UUID, Vehicle> vehicles = new Object2ObjectLinkedOpenHashMap<>();
	/**
	 * Per-{@code (sourceId, destinationId, ingredientId)} dedup guard so the per-tick
	 * dispatcher doesn't launch duplicate robots while one is already in flight for the
	 * same restock leg. Cleared on arrival inside {@link #advanceVehicles}.
	 */
	private final java.util.Set<String> activeRestockKeys = new java.util.HashSet<>();
	private Money balance;
	private final GameClock clock = new GameClock();
	/** Last game-day (gameMinutes / 1440) on which {@link #applyDailyUpkeepIfDayChanged} ran.
	 * {@code Long.MIN_VALUE} primes the comparison so the very first tick after game-start
	 * doesn't immediately bill upkeep at game-minute 0. */
	private long lastUpkeepDay = Long.MIN_VALUE;

	public GameState(ContentRegistry registry) {
		this(registry, Money.of(GameConstants.STARTING_BALANCE));
	}

	/** Test-friendly constructor. */
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

		// Phase 3 only opens T1 recipes for direct factory placement; higher tiers gate behind
		// progression. Farms and restaurants are exempt — farms grow whatever the recipe says,
		// and restaurants merely accept deliveries of the named dish.
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
			// Density cap: reject if a same-kind building already sits within the configured
			// minimum spacing. Prevents spamming a hundred farms onto the same park.
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
				// Phase 6: a restaurant accepts deliveries of `recipeId` (its house dish). Order
				// queue + patience timer are tracked separately so this record stays a value type.
				case RESTAURANT -> Restaurant.of(UUID.randomUUID(), lat, lon, recipeId, templateId);
			};
			balance = balance.minus(cost);
			buildings.put(building.id(), building);
			return new PlacementResult.Success(building, balance);
		} finally {
			lock.unlock();
		}
	}

	public Optional<Building> demolishBuilding(UUID id) {
		lock.lock();
		try {
			return Optional.ofNullable(buildings.remove(id));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase 5: reorder a factory's operations. Returns the updated building, or empty if no
	 * such factory exists. Throws {@link IllegalArgumentException} if the new list is not a
	 * permutation of the existing one.
	 */
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

	/** Test helper. */
	public void reset() {
		lock.lock();
		try {
			buildings.clear();
			orderQueues.clear();
			vehicles.clear();
			activeRestockKeys.clear();
			balance = Money.of(GameConstants.STARTING_BALANCE);
			clock.reset();
			lastUpkeepDay = Long.MIN_VALUE;
		} finally {
			lock.unlock();
		}
	}

	// ─── Phase 6: restaurant orders ───────────────────────────────────────

	/**
	 * Enqueue a pending order against an existing restaurant. Returns the order, or empty if the
	 * id doesn't belong to a restaurant.
	 */
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

	/**
	 * Mark an order as delivered. Returns an {@link OrderOutcome} (FULFILLED / LATE) plus the
	 * applied payout, new balance, and new restaurant reputation. Empty if the order id is
	 * unknown. Reputation deltas + payouts are applied here so the engine doesn't have to
	 * reach back into private state.
	 */
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

	/**
	 * Drop every order whose deadline has passed. Returns events with the per-restaurant
	 * reputation hit already applied so callers (the simulation engine) can broadcast them
	 * verbatim onto the SSE stream.
	 */
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

	/**
	 * Phase-8 production tick. For every owned farm/factory, count the number of full
	 * production cycles that elapsed since {@code cycleStartedAtGameMinutes}, increment
	 * {@code producedUnits} accordingly, and re-anchor the cycle start to the most recent
	 * boundary so the next tick picks up cleanly. No-op for restaurants. The frontend doesn't
	 * need this to render the progress ring (it derives progress from the cycle anchor +
	 * the live clock), but it does need it for the integer {@code producedUnits} count
	 * surfaced in the farm/factory drawers.
	 */
	public void advanceProduction() {
		lock.lock();
		try {
			long now = clock.getGameMinutes();
			for (Building building : buildings.values().toArray(new Building[0])) {
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
						// Phase-10: factory cycles are now gated on inputs. We can only
						// complete as many cycles as the stockpile can satisfy. Recipes
						// without inputs (defensive — shouldn't really exist for factories)
						// fall through to the unbounded path. Recipes whose ingredient
						// definition has been removed mid-game (modded content) also fall
						// through, mirroring the Phase-7 lenient-fulfill philosophy.
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
							// Phase-11: when stalled (no inputs), pin the cycle anchor to "now"
							// so the frontend progress ring stays at 0% rather than sweeping
							// through phantom partial progress. As soon as a delivery arrives,
							// the next cycle starts cleanly from a fresh anchor and the player
							// sees real progress, not a backlog.
							if (factory.cycleStartedAtGameMinutes() != now) {
								buildings.put(factory.id(), new Factory(
									factory.id(), factory.lat(), factory.lon(), factory.recipeId(),
									factory.operations(), now,
									factory.cycleDurationGameMinutes(), factory.producedUnits(),
									factory.refrigerated(), factory.inputStockpile()));
							}
							continue;
						}
						// If the factory completed every elapsed cycle, anchor to the natural
						// cycle boundary. Otherwise (partial completion, ran out of inputs
						// mid-window), anchor to "now" so the next cycle starts fresh once a
						// delivery arrives — same stall semantics as the completed==0 branch.
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

	/**
	 * Phase-8 task 4: atomically reserve one finished unit from a farm/factory's stockpile.
	 * Returns the updated building on success, or empty if the id doesn't belong to a
	 * producer or its {@code producedUnits == 0}. The delivery dispatcher calls this just
	 * before launching a van so an empty source can't supply an order.
	 */
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

	/**
	 * Phase-10: deliver {@code quantity} units of {@code ingredientId} to a factory's
	 * input stockpile. Returns the updated factory, or empty if the id doesn't belong to a
	 * factory. Called by the farm→factory delivery walker on arrival. Atomic under the lock.
	 */
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

	/**
	 * Phase-8 task 6: spend {@link GameConstants#REFRIGERATION_UPGRADE_COST} to flip the
	 * refrigerated flag on a factory. Returns the updated factory, or empty if the id isn't
	 * a factory; throws {@link IllegalStateException} when the wallet can't cover the cost
	 * so the controller can surface a 402 PAYMENT_REQUIRED. Already-refrigerated factories
	 * are a no-op (no double charge).
	 */
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

	/**
	 * Resolve the production cycle duration for a building kind + recipe pair. Farms run a
	 * single grow/harvest cycle; factories chain through every operation, so their cycle
	 * scales with the operation count. Always at least one game-minute so a misconfigured
	 * recipe doesn't divide-by-zero downstream.
	 */
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
			return GameConstants.DEFAULT_RESTAURANT_PAYOUT; // sensible fallback for fixture restaurants
		}
		return registry.findRestaurantTemplate(templateId)
			.map(RestaurantTemplate::basePayout)
			.orElse(GameConstants.DEFAULT_RESTAURANT_PAYOUT);
	}

	/**
	 * Mark an order as spoiled in transit. Removes it from the queue and applies the
	 * "missed delivery" reputation hit (the cargo never arrived in usable form). Returns the
	 * outcome carrying {@link OrderResult#SPOILED} and the updated reputation, or empty if
	 * the order is already gone (already fulfilled / expired by the engine).
	 */
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

	/**
	 * Phase-7 daily upkeep. If the game-clock has rolled into a new game-day since this
	 * method last ran, deduct each owned building's {@link BuildingKind#dailyUpkeep} from the
	 * wallet (clamped at zero — going into debt is a Phase-8 concern). Returns the total
	 * amount deducted on the day boundary, or {@code 0} when the day hasn't changed yet.
	 */
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

	/** Outcome of a fulfilled order — exposed back to the API so it can echo the new wallet. */
	public record OrderOutcome(OrderResult result, long payout, long newBalance, double newReputation) {
	}

	/** Carries an expired order plus the post-hit reputation of its restaurant. */
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
	 * Phase-12: spawn a robot carrying one ingredient from a producer to a downstream
	 * factory or restaurant. Atomically debits one finished unit from the source's
	 * stockpile (or returns empty when the source is dry / unknown / not a producer).
	 * The path is straight-line for now — OSM street pathfinding will populate intermediate
	 * waypoints in a later phase without changing this signature.
	 *
	 * @param sourceId          farm or factory shipping the cargo
	 * @param destinationId     factory (restock) or restaurant (order) receiving it
	 * @param ingredientId      what's riding the robot
	 * @param quantity          how many units (≥ 1)
	 * @param orderId           non-null for restaurant-bound robots; null for factory restocks
	 * @param spoilageDeadline  game-minute past which the cargo spoils, or null
	 * @return spawn event when successful; empty if the source had no stock
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
			// Debit one unit from the producer atomically with the spawn so a parallel
			// dispatch can't drain the same physical unit twice.
			Optional<Building> debited = consumeProducedUnitInternal(source);
			if (debited.isEmpty()) {
				return Optional.empty();
			}
			buildings.put(sourceId, debited.get());

			List<LatLon> path = List.of(
				new LatLon(source.lat(), source.lon()),
				new LatLon(destination.lat(), destination.lon()));
			long now = clock.getGameMinutes();
			double distanceMeters = haversineMetres(
				source.lat(), source.lon(), destination.lat(), destination.lon());
			long travel = Math.max(1L, Math.round(
				distanceMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));
			Robot robot = new Robot(
				UUID.randomUUID(),
				sourceId,
				destinationId,
				Map.of(ingredientId, quantity),
				path,
				now,
				now + travel,
				orderId,
				spoilageDeadline);
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
	 * Phase-12: walk every in-flight vehicle. Vehicles whose
	 * {@link Robot#hasArrived(long)} returns true at the current game-minute are removed
	 * and their cargo is applied to the destination — input stockpile for factories,
	 * fulfil/spoil for restaurant orders. Returns the {@link VehicleEvent.Arrived} list
	 * the engine needs to push onto the SSE stream (plus any
	 * {@link com.dimsumdetours.sim.model.OrderEvent.Fulfilled} the caller wants to relay).
	 */
	public ArrivalBatch advanceVehicles() {
		lock.lock();
		try {
			long now = clock.getGameMinutes();
			List<VehicleEvent.Arrived> arrived = new ArrayList<>();
			List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> fulfilments = new ArrayList<>();
			for (Vehicle vehicle : vehicles.values().toArray(new Vehicle[0])) {
				if (!(vehicle instanceof Robot robot) || !robot.hasArrived(now)) {
					continue;
				}
				vehicles.remove(robot.id());
				if (robot.orderId() == null) {
					activeRestockKeys.remove(restockKey(
						robot.sourceBuildingId(),
						robot.destinationBuildingId(),
						firstCargoIngredient(robot)));
				}
				@Nullable OrderResult orderResult = applyArrival(robot, now, fulfilments);
				arrived.add(new VehicleEvent.Arrived(
					robot.id(),
					robot.destinationBuildingId(),
					robot.orderId(),
					orderResult,
					now));
			}
			return new ArrivalBatch(arrived, fulfilments);
		} finally {
			lock.unlock();
		}
	}

	/** Bundle returned from {@link #advanceVehicles()} so the engine can fan-out to two sinks. */
	public record ArrivalBatch(
		List<VehicleEvent.Arrived> vehicleEvents,
		List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> orderEvents
	) {
	}

	/**
	 * Apply a robot's cargo to its destination. Factory restock → bump input stockpile.
	 * Restaurant order → fulfil (or spoil) and add a corresponding {@code OrderEvent}
	 * to the running list. Returns the {@link OrderResult} for the SSE arrival event,
	 * or null for restock arrivals.
	 */
	private @Nullable OrderResult applyArrival(
		Robot robot,
		long now,
		List<com.dimsumdetours.sim.model.OrderEvent.Fulfilled> fulfilments
	) {
		Building destination = buildings.get(robot.destinationBuildingId());
		Map.Entry<String, Integer> only = robot.cargo().entrySet().iterator().next();
		String ingredientId = only.getKey();
		int quantity = only.getValue();

		if (robot.orderId() == null) {
			// Factory restock — credit the destination's input stockpile, no order math.
			if (destination instanceof Factory factory) {
				buildings.put(factory.id(), factory.withInputDelivered(ingredientId, quantity));
			}
			return null;
		}

		// Restaurant-bound robot. Decide spoiled vs. fulfilled, then settle the order.
		boolean spoiled = robot.spoilageDeadlineGameMinutes() != null
			&& now > robot.spoilageDeadlineGameMinutes();
		Optional<OrderOutcome> outcome = spoiled
			? spoilOrder(robot.destinationBuildingId(), robot.orderId())
			: fulfillOrder(robot.destinationBuildingId(), robot.orderId());
		outcome.ifPresent(o -> fulfilments.add(new com.dimsumdetours.sim.model.OrderEvent.Fulfilled(
			robot.orderId(),
			robot.destinationBuildingId(),
			o.result(),
			o.payout(),
			o.newBalance(),
			o.newReputation(),
			now)));
		return outcome.map(OrderOutcome::result).orElse(null);
	}

	/**
	 * Phase-12 dedup guard accessor for the dispatcher: true iff a restock robot for
	 * {@code (sourceId → destinationId, ingredientId)} is already in flight.
	 */
	public boolean hasActiveRestock(UUID sourceId, UUID destinationId, String ingredientId) {
		lock.lock();
		try {
			return activeRestockKeys.contains(restockKey(sourceId, destinationId, ingredientId));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase-12 dedup guard accessor: true iff any in-flight vehicle is already carrying
	 * {@code orderId}. Prevents the dispatcher from double-booking an order whose ENQUEUE
	 * event the engine just emitted.
	 */
	public boolean hasInFlightOrder(UUID orderId) {
		lock.lock();
		try {
			for (Vehicle vehicle : vehicles.values()) {
				if (orderId.equals(vehicle.orderId())) {
					return true;
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

	private static String firstCargoIngredient(Robot robot) {
		return robot.cargo().keySet().iterator().next();
	}

	/** Internal twin of {@link #tryConsumeProducedUnit} that runs under the caller's lock. */
	private Optional<Building> consumeProducedUnitInternal(Building source) {
		if (source instanceof Farm farm) {
			return farm.withProducedUnitConsumed().map(updated -> (Building) updated);
		}
		if (source instanceof Factory factory) {
			return factory.withProducedUnitConsumed().map(updated -> (Building) updated);
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
				clock.getSpeedMultiplier(),
				!clock.isPaused());
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
			clock.setPaused(paused);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Advance the clock by {@code deltaGameMinutes}. Called from the simulation engine each tick.
	 */
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

	/** Snapshot record returned to the API/SSE layer. */
	public record ClockSnapshot(long gameMinutes, int dayOfWeek, int minuteOfDay, int speed, boolean playing) {
	}

	/** Great-circle distance between two lat/lon pairs, in metres. WGS-84 mean radius.
	 * Public so the Phase-12 dispatcher can pick the nearest producer without rolling
	 * its own copy. */
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
}
