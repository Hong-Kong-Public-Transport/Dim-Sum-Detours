package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract autonomous shipment carrier. A vehicle picks up cargo at
 * {@link #sourceBuildingId()}, follows {@link #path()} at {@link #metersPerGameMinute()},
 * and either applies its cargo to {@link #destinationBuildingId()} on arrival (when
 * {@link #handoff()} is null) or hands the cargo off to the next leg's vehicle
 * (when handoff is set). Cargo flows through the chain verbatim — no per-leg
 * transfer fees, no per-leg quantity loss.
 *
 * <p>Sealed to {@link Robot} (≤ 5 km autonomous legs) and {@link Bus} (multi-leg
 * GTFS rides, added Phase 16). {@code Train} is reserved for a later phase.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "kind",
	visible = true
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = Robot.class, name = "ROBOT"),
	@JsonSubTypes.Type(value = Bus.class, name = "BUS")
})
public sealed interface Vehicle permits Robot, Bus {

	UUID id();

	VehicleKind kind();

	UUID sourceBuildingId();

	UUID destinationBuildingId();

	/**
	 * Cargo riding the vehicle, keyed by {@code ingredientId}. A robot dispatched against
	 * a restaurant order carries its house dish ingredient (qty = order.quantity); a
	 * factory-restock robot carries the input ingredient (qty = recipe per-cycle quantity).
	 * For chained legs (multi-leg plans), the same map is propagated unchanged from
	 * vehicle to vehicle through the handoff.
	 */
	Map<String, Integer> cargo();

	/**
	 * Ordered list of waypoints. Always at least 2 entries — {@code path[0]} is the
	 * source position, the last entry is the destination of THIS leg (which may be a
	 * GTFS stop rather than the final building when {@link #handoff()} is non-null).
	 */
	List<LatLon> path();

	/** Game-minute the vehicle was created. Frontend interpolates position from this. */
	long spawnedAtGameMinutes();

	/**
	 * Phase-17: game-minute at which the vehicle finishes loading at its source and
	 * starts moving. Always {@code >= spawnedAtGameMinutes} and {@code <= arrivesAtGameMinutes}.
	 * Until this minute the frontend renders the vehicle stationary at {@code path[0]};
	 * after it the vehicle interpolates along the path. Encoding a loading window
	 * directly on the spawn event lets every Spawned frame stand alone — the frontend
	 * doesn't need a second SSE frame mid-flight to position the marker correctly,
	 * which fixes the symptom where high game-speeds caused arrivals to "teleport"
	 * because the spawn frame and the arrival frame landed inside the same animation
	 * tick.
	 */
	long departsAtGameMinutes();

	/**
	 * Game-minute the vehicle is expected to arrive (= {@code spawnedAt + pathLength /
	 * metersPerGameMinute} for robots, GTFS-scheduled travel time for buses). Stored
	 * so the dispatcher and the on-arrival branch agree on the deadline without
	 * recomputing path length.
	 */
	long arrivesAtGameMinutes();

	/**
	 * Optional restaurant-order id this vehicle is fulfilling. Null for factory-restock
	 * vehicles — those simply credit the destination factory's input stockpile on arrival.
	 */
	@Nullable UUID orderId();

	/**
	 * Game-minute at which the cargo's freshness clock runs out, or {@code null} for
	 * non-perishable / refrigerated-source cargo. Past-deadline arrivals route to
	 * spoilage instead of fulfilment. Carried through every leg of a multi-leg chain
	 * so the bus ride counts toward spoilage, not just the robot legs.
	 */
	@Nullable Long spoilageDeadlineGameMinutes();

	/**
	 * Casual-biking speed for robots ({@link Robot} returns 170 m/game-min); GTFS-driven
	 * scheduled speed averaged across the trip for buses ({@link Bus} computes from
	 * arrivesAt − spawnedAt). The frontend uses it for marker interpolation.
	 */
	double metersPerGameMinute();

	/**
	 * Phase-16: instruction for what to spawn when this vehicle arrives. {@code null}
	 * means apply cargo to {@link #destinationBuildingId()} normally; non-null means
	 * despawn this vehicle and spawn a fresh leg carrying the same cargo, the new leg's
	 * own handoff being {@link VehicleHandoff#nextHandoff()}.
	 */
	@Nullable VehicleHandoff handoff();
}

