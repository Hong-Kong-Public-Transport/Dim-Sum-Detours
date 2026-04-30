package com.dimsumdetours.sim.model;

import java.util.UUID;

/**
 * Phase 6 event broadcast onto the {@code SimulationEngine.orderStream()} multicast sink.
 * Sealed so subscribers can pattern-match exhaustively.
 *
 * <p>Lives in the framework-agnostic {@code sim/} package — no Spring imports.
 */
public sealed interface OrderEvent {

	String type();

	UUID orderId();

	UUID restaurantId();

	long gameMinutes();

	/** A new order was enqueued against a restaurant. */
	record Enqueued(Order order, long gameMinutes) implements OrderEvent {
		@Override
		public String type() {
			return "ENQUEUED";
		}

		@Override
		public UUID orderId() {
			return order.id();
		}

		@Override
		public UUID restaurantId() {
			return order.restaurantId();
		}
	}

	/** A pending order was delivered. {@code result} is FULFILLED or LATE. */
	record Fulfilled(
		UUID orderId,
		UUID restaurantId,
		OrderResult result,
		long payout,
		long newBalance,
		double newReputation,
		long gameMinutes
	) implements OrderEvent {
		@Override
		public String type() {
			return "FULFILLED";
		}
	}

	/** A pending order's deadline elapsed without delivery. */
	record Expired(
		UUID orderId,
		UUID restaurantId,
		double newReputation,
		long gameMinutes
	) implements OrderEvent {
		@Override
		public String type() {
			return "EXPIRED";
		}
	}
}

