package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.OrderResult;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Vehicle lifecycle events broadcast to the SSE stream. The frontend uses {@link Spawned}
 * to add a marker (with the full path so it can interpolate locally) and {@link Arrived}
 * to remove it; no per-tick "moved" event so bandwidth scales with throughput, not
 * vehicle count.
 *
 * <p>Phase-13 fix: the sealed interface is annotated with {@link JsonTypeInfo} so Jackson
 * emits a {@code "type"} discriminator on the wire. Without this, Spring's default
 * {@code ObjectMapper} only serialises record components — and the {@code type()} method
 * is a non-component override, so it was silently dropped. The frontend's
 * {@code applyEvent} switches on {@code event.type}, so missing discriminators meant
 * every spawn/arrive event fell through and no robots ever appeared on the map.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	visible = true
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = VehicleEvent.Spawned.class, name = "SPAWNED"),
	@JsonSubTypes.Type(value = VehicleEvent.Arrived.class, name = "ARRIVED"),
	@JsonSubTypes.Type(value = VehicleEvent.RobotArrivedAtStop.class, name = "ROBOT_ARRIVED_AT_STOP")
})
public sealed interface VehicleEvent {

	String type();

	/** Game-minute the event was emitted; useful for frontend resync after a reload. */
	long gameMinutes();

	/**
	 * A vehicle just dispatched. The full {@link Vehicle} is sent
	 * (Jackson {@code "kind"} discriminator stays for forward
	 * compatibility) and the frontend computes positions purely from
	 * {@code spawnedAt + path + speed}. Phase-21: the only concrete
	 * kind is {@link Robot} now that {@code Bus} has been removed —
	 * transit cargo flows through {@code CARGO_LOADED} /
	 * {@code CARGO_UNLOADED} events on a separate channel.
	 */
	record Spawned(Vehicle vehicle, long gameMinutes) implements VehicleEvent {
		@Override
		public String type() {
			return "SPAWNED";
		}
	}

	/**
	 * A robot reached its destination and was removed. The optional {@code orderResult}
	 * describes what happened to the order it was carrying (FULFILLED / LATE / SPOILED);
	 * factory-restock robots leave it null.
	 */
	record Arrived(
		UUID vehicleId,
		UUID destinationBuildingId,
		@Nullable UUID orderId,
		@Nullable OrderResult orderResult,
		long gameMinutes
	) implements VehicleEvent {
		@Override
		public String type() {
			return "ARRIVED";
		}
	}

	/**
	 * Phase-21: a first-mile robot reached its boarding stop and was
	 * despawned (its cargo flipped into a {@code WaitingCargo} queue
	 * keyed by {@code (boardingStopId, routeId)}). Distinct from
	 * {@link Arrived} because no cargo is delivered to a destination —
	 * the cargo is mid-route. Frontend uses this to remove the robot
	 * sprite the moment the despawn happens, without having to infer
	 * "robot vanished but had a transit boarding plan" from a generic
	 * Arrived event. No emitter wired yet — the boarding state machine
	 * publishes onto this channel.
	 */
	record RobotArrivedAtStop(
		UUID vehicleId,
		String boardingStopId,
		String routeId,
		long gameMinutes
	) implements VehicleEvent {
		@Override
		public String type() {
			return "ROBOT_ARRIVED_AT_STOP";
		}
	}
}

