package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Phase 6 scaffolding — a placed restaurant accepts deliveries of a specific recipe (its
 * "house dish"). Reputation drives whether the restaurant stays open; orders + patience
 * timers will be modelled by a separate {@code OrderQueue} (added later this phase) so this
 * record can stay a small, pure value type.
 *
 * <p>Restaurants are NOT player-built in the long run — they auto-spawn as the city grows
 * (see {@code SimulationEngine}). The constructor is exposed so Phase 6 can place a fixture
 * for the first end-to-end delivery test before auto-spawn lands.
 *
 * @param templateId optional id of the {@link RestaurantTemplate} this restaurant was placed
 *                   from. Drives payout + patience defaults; {@code null} for legacy fixtures
 *                   (in which case payout falls back to a sensible default).
 */
public record Restaurant(
	UUID id,
	double lat,
	double lon,
	String recipeId,
	double reputation,
	boolean closed,
	@Nullable String templateId,
	/**
	 * Phase-13: lifetime count of orders this restaurant has successfully fulfilled
	 * (FULFILLED + LATE; spoiled / expired orders don't count). Surfaced in the
	 * restaurant info drawer so the player has a concrete signal of how productive a
	 * site has been. Bumped exclusively from
	 * {@link com.dimsumdetours.sim.state.GameState#fulfillOrder}.
	 */
	long fulfilledOrders
) implements Building {

	public Restaurant {
		if (reputation < 0.0 || reputation > 1.0) {
			throw new IllegalArgumentException("reputation must be in [0.0, 1.0], got " + reputation);
		}
		if (fulfilledOrders < 0L) {
			throw new IllegalArgumentException(
				"fulfilledOrders must be non-negative, got " + fulfilledOrders);
		}
	}

	/** Backwards-compatible 7-arg constructor used by existing tests + factories — defaults
	 * the {@code fulfilledOrders} counter to 0. */
	public Restaurant(
		UUID id,
		double lat,
		double lon,
		String recipeId,
		double reputation,
		boolean closed,
		@Nullable String templateId
	) {
		this(id, lat, lon, recipeId, reputation, closed, templateId, 0L);
	}

	@Override
	public BuildingKind kind() {
		return BuildingKind.RESTAURANT;
	}

	/** Convenience: a freshly-placed restaurant starts at full reputation, open for business. */
	public static Restaurant of(UUID id, double lat, double lon, String recipeId) {
		return new Restaurant(id, lat, lon, recipeId, 1.0, false, null, 0L);
	}

	public static Restaurant of(UUID id, double lat, double lon, String recipeId, @Nullable String templateId) {
		return new Restaurant(id, lat, lon, recipeId, 1.0, false, templateId, 0L);
	}

	/** Returns a copy with reputation clamped into the legal range. Auto-closes once reputation
	 * dips below {@link com.dimsumdetours.config.GameConstants#RESTAURANT_CLOSE_REPUTATION_THRESHOLD}. */
	public Restaurant withReputation(double newReputation) {
		double clamped = Math.max(0.0, Math.min(1.0, newReputation));
		boolean nowClosed = closed
			|| clamped < com.dimsumdetours.config.GameConstants.RESTAURANT_CLOSE_REPUTATION_THRESHOLD;
		return new Restaurant(id, lat, lon, recipeId, clamped, nowClosed, templateId, fulfilledOrders);
	}

	/** Returns a copy with the fulfilled-orders counter bumped by one. Called by
	 * {@link com.dimsumdetours.sim.state.GameState#fulfillOrder} on a successful settlement
	 * (FULFILLED or LATE) — spoiled / expired orders deliberately don't count. */
	public Restaurant withFulfilledIncremented() {
		return new Restaurant(id, lat, lon, recipeId, reputation, closed, templateId, fulfilledOrders + 1L);
	}
}
