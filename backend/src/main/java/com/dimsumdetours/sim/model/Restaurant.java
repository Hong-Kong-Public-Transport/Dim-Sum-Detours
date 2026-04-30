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
	@Nullable String templateId
) implements Building {

	public Restaurant {
		if (reputation < 0.0 || reputation > 1.0) {
			throw new IllegalArgumentException("reputation must be in [0.0, 1.0], got " + reputation);
		}
	}

	@Override
	public BuildingKind kind() {
		return BuildingKind.RESTAURANT;
	}

	/** Convenience: a freshly-placed restaurant starts at full reputation. */
	public static Restaurant of(UUID id, double lat, double lon, String recipeId) {
		return new Restaurant(id, lat, lon, recipeId, 1.0, null);
	}

	public static Restaurant of(UUID id, double lat, double lon, String recipeId, @Nullable String templateId) {
		return new Restaurant(id, lat, lon, recipeId, 1.0, templateId);
	}

	/** Returns a copy with reputation clamped into the legal range. */
	public Restaurant withReputation(double newReputation) {
		double clamped = Math.max(0.0, Math.min(1.0, newReputation));
		return new Restaurant(id, lat, lon, recipeId, clamped, templateId);
	}
}
