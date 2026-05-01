package com.dimsumdetours.sim.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Phase 6 event broadcast onto the {@code SimulationEngine.orderStream()} multicast sink.
 * Sealed so subscribers can pattern-match exhaustively.
 *
 * <p>Lives in the framework-agnostic {@code sim/} package — no Spring imports.
 *
 * <p>Phase-13 fix: annotated with {@link JsonTypeInfo} so Jackson emits a {@code "type"}
 * discriminator on the wire. The frontend's {@code RestaurantService.applyEvent}
 * switches on {@code event.type}; without the discriminator every event was silently
 * dropped, so restaurants appeared not to receive any orders even though the generator
 * was successfully enqueueing them server-side.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	visible = true
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = OrderEvent.Enqueued.class, name = "ENQUEUED"),
	@JsonSubTypes.Type(value = OrderEvent.Fulfilled.class, name = "FULFILLED"),
	@JsonSubTypes.Type(value = OrderEvent.Expired.class, name = "EXPIRED")
})
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

