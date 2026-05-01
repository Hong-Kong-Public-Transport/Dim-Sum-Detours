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
	@JsonSubTypes.Type(value = VehicleEvent.Arrived.class, name = "ARRIVED")
})
public sealed interface VehicleEvent {

	String type();

	/** Game-minute the event was emitted; useful for frontend resync after a reload. */
	long gameMinutes();

	/**
	 * A robot just dispatched. {@link Robot} is sent in full so the frontend can compute
	 * positions purely from {@code spawnedAt + path + speed}.
	 */
	record Spawned(Robot robot, long gameMinutes) implements VehicleEvent {
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
}

