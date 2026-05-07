package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-21: autonomous shipment carrier. After the mode-unification
 * deletion of {@code Bus}, {@code Robot} is the only implementer; this
 * interface stays to keep the existing wire shape ({@code "kind":
 * "ROBOT"} discriminator) stable for the frontend's vehicle service.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>A vehicle picks up cargo at {@link #sourceBuildingId()},
 *       follows {@link #path()} at {@link #metersPerGameMinute()}.</li>
 *   <li>If {@link Robot#boarding()} is non-null, the vehicle is a
 *       <em>first-mile</em> robot — on arrival the boarding state
 *       machine in {@code GameState.advanceVehicles} drains its cargo
 *       into a {@link WaitingCargo} keyed by {@code (boardingStopId,
 *       routeId)} and emits a {@link VehicleEvent.RobotArrivedAtStop}
 *       (the cargo never reaches a destination directly). The boarding
 *       scan inside the same tick (or a later tick) attaches the
 *       cargo to whichever ambient transit run crosses the stop.</li>
 *   <li>Otherwise the vehicle is a <em>direct</em> or
 *       <em>connecting</em> robot — on arrival the cargo is applied
 *       to {@link #destinationBuildingId()} (input stockpile for
 *       factories, fulfil/spoil for restaurant orders).</li>
 * </ul>
 *
 * <p>The Jackson type discriminator is retained so the legacy
 * frontend's {@code vehicle.kind} field continues to deserialise to
 * {@code "ROBOT"} verbatim — frontend Phase-21 cleanup (step 8) drops
 * the field when no producer ever emits a different kind.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "kind",
	visible = true
)
public interface Vehicle {

	UUID id();

	UUID sourceBuildingId();

	UUID destinationBuildingId();

	/**
	 * Cargo riding the vehicle, keyed by {@code ingredientId}. A robot dispatched against
	 * a restaurant order carries its house dish ingredient (qty = order.quantity); a
	 * factory-restock robot carries the input ingredient (qty = recipe per-cycle quantity).
	 */
	Map<String, Integer> cargo();

	/**
	 * Ordered list of waypoints. Always at least 2 entries — {@code path[0]} is the
	 * source position, the last entry is the destination of THIS leg (which may be a
	 * GTFS boarding stop when {@link Robot#boarding()} is non-null).
	 */
	List<LatLon> path();

	/** Game-minute the vehicle was created. Frontend interpolates position from this. */
	long spawnedAtGameMinutes();

	/**
	 * Phase-17: game-minute at which the vehicle finishes loading at its source and
	 * starts moving. Always {@code >= spawnedAtGameMinutes} and
	 * {@code <= arrivesAtGameMinutes}. Until this minute the frontend renders the
	 * vehicle stationary at {@code path[0]}; after it the vehicle interpolates along
	 * the path.
	 */
	long departsAtGameMinutes();

	/**
	 * Game-minute the vehicle is expected to arrive (= {@code spawnedAt + pathLength /
	 * metersPerGameMinute}). Stored so the dispatcher and the on-arrival branch agree
	 * on the deadline without recomputing path length.
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
	 * spoilage instead of fulfilment. Carried through every leg (first-mile, transit,
	 * connecting) so the bus ride counts toward spoilage too.
	 */
	@Nullable Long spoilageDeadlineGameMinutes();

	/**
	 * Casual-biking speed for robots ({@link Robot} returns 170 m/game-min). The
	 * frontend uses it for marker interpolation between path waypoints.
	 */
	double metersPerGameMinute();
}

