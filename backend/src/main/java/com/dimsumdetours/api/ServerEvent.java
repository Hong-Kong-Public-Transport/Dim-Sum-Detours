package com.dimsumdetours.api;

import com.dimsumdetours.sim.model.MilestoneEvent;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.vehicle.CargoEvent;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Phase-19 Phase-G: the unified server → client envelope. One SSE channel
 * ({@code GET /api/game/stream}) carries every event the frontend reacts
 * to: clock anchors, vehicle lifecycle, order lifecycle, milestone
 * unlocks, and the catch-all game-event bucket. The {@code "type"}
 * discriminator on the wire lets the client fan out into per-domain
 * services without opening five separate {@code EventSource} connections
 * (browsers cap SSE at 6 per origin).
 *
 * <p>The legacy per-channel endpoints (clock/stream, orders/stream, …)
 * are still wired for backward compatibility + debug ergonomics, but
 * the production frontend opens only the unified stream.
 *
 * <p>Each variant wraps a payload that already carries its own envelope
 * fields ({@code gameMinutes}, {@code serverWallClockMs}, {@code
 * worldEpoch}); this wrapper exists purely to disambiguate event types
 * over the wire.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
	property = "type", visible = true)
@JsonSubTypes({
	@JsonSubTypes.Type(value = ServerEvent.Clock.class, name = "CLOCK"),
	@JsonSubTypes.Type(value = ServerEvent.Vehicle.class, name = "VEHICLE"),
	@JsonSubTypes.Type(value = ServerEvent.Order.class, name = "ORDER"),
	@JsonSubTypes.Type(value = ServerEvent.Milestone.class, name = "MILESTONE"),
	@JsonSubTypes.Type(value = ServerEvent.Game.class, name = "GAME"),
	@JsonSubTypes.Type(value = ServerEvent.Cargo.class, name = "CARGO"),
})
public sealed interface ServerEvent {

	String type();

	/** Periodic clock anchor + state-transition emit (pause / resume / setSpeed / reset). */
	record Clock(GameState.ClockSnapshot payload) implements ServerEvent {
		@Override public String type() { return "CLOCK"; }
	}

	/** Vehicle lifecycle (Spawned / Arrived). */
	record Vehicle(VehicleEvent payload) implements ServerEvent {
		@Override public String type() { return "VEHICLE"; }
	}

	/** Order lifecycle (Enqueued / Fulfilled / Expired). */
	record Order(OrderEvent payload) implements ServerEvent {
		@Override public String type() { return "ORDER"; }
	}

	/** Milestone unlocks. */
	record Milestone(MilestoneEvent payload) implements ServerEvent {
		@Override public String type() { return "MILESTONE"; }
	}

	/** Catch-all bucket — wallet, building state, restaurant closure, world reset. */
	record Game(GameEvent payload) implements ServerEvent {
		@Override public String type() { return "GAME"; }
	}

	/**
	 * Phase-21: cargo lifecycle on transit runs ({@code RUN_STARTED} /
	 * {@code CARGO_LOADED} / {@code CARGO_UNLOADED} / {@code RUN_FINISHED}).
	 * Lets the frontend scale ambient transit sprites by manifest count
	 * without needing a backend {@code TransitVehicle} object for every
	 * run on the map. No emitter wired yet — the dispatcher rewrite
	 * publishes onto this channel.
	 */
	record Cargo(CargoEvent payload) implements ServerEvent {
		@Override public String type() { return "CARGO"; }
	}
}

