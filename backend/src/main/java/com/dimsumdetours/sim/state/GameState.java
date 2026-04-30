package com.dimsumdetours.sim.state;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.GameClock;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderResult;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
	private Money balance;
	private final GameClock clock = new GameClock();

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
					yield new Farm(UUID.randomUUID(), lat, lon, recipeId, firstOutput.ingredientId());
				}
				case FACTORY -> new Factory(UUID.randomUUID(), lat, lon, recipeId, recipe.operations());
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
			balance = Money.of(GameConstants.STARTING_BALANCE);
			clock.reset();
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
			Restaurant updated = restaurant.withReputation(restaurant.reputation() + delta);
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

	private long lookupBasePayout(Restaurant restaurant) {
		String templateId = restaurant.templateId();
		if (templateId == null) {
			return GameConstants.DEFAULT_RESTAURANT_PAYOUT; // sensible fallback for fixture restaurants
		}
		return registry.findRestaurantTemplate(templateId)
			.map(RestaurantTemplate::basePayout)
			.orElse(GameConstants.DEFAULT_RESTAURANT_PAYOUT);
	}

	/** Outcome of a fulfilled order — exposed back to the API so it can echo the new wallet. */
	public record OrderOutcome(OrderResult result, long payout, long newBalance, double newReputation) {
	}

	/** Carries an expired order plus the post-hit reputation of its restaurant. */
	public record ExpiredOrderEvent(Order order, double newReputation) {
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

	/** Great-circle distance between two lat/lon pairs, in metres. WGS-84 mean radius. */
	private static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
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
