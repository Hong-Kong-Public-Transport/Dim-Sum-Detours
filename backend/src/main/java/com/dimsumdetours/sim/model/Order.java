package com.dimsumdetours.sim.model;

import java.util.UUID;

/**
 * Phase-6 value type: a pending delivery request from a restaurant. Patience is encoded as the
 * absolute game-minute by which the order must be fulfilled — this avoids per-tick mutation of
 * the order itself and lets the engine compare against the clock cheaply.
 *
 * <p>Lives in the framework-agnostic {@code sim/} package — pure data, no Spring imports.
 */
public record Order(
	UUID id,
	UUID restaurantId,
	String recipeId,
	int quantity,
	long createdAtGameMinutes,
	long deadlineGameMinutes
) {

	public Order {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, got " + quantity);
		}
		if (deadlineGameMinutes <= createdAtGameMinutes) {
			throw new IllegalArgumentException(
				"deadline must be after creation: createdAt=" + createdAtGameMinutes
					+ ", deadline=" + deadlineGameMinutes);
		}
	}

	/** Game-minutes still on the clock before this order expires (negative if already late). */
	public long remainingMinutes(long currentGameMinutes) {
		return deadlineGameMinutes - currentGameMinutes;
	}
}

