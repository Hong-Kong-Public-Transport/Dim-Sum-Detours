package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract autonomous shipment carrier. A vehicle picks up cargo at
 * {@link #sourceBuildingId()}, follows {@link #path()} at {@link #metersPerGameMinute()},
 * and applies its cargo to {@link #destinationBuildingId()} on arrival before being
 * removed from the simulation. The contract is deliberately verb-free: spawning,
 * advancing, and arrival handling all live on
 * {@link com.dimsumdetours.sim.state.GameState} so the model stays a value type.
 *
 * <p>Sealed to {@link Robot} for now. Future {@code Bus} and {@code Train} subtypes will
 * carry route + schedule fields layered on top of the same path-walking core.
 */
public sealed interface Vehicle permits Robot {

	UUID id();

	VehicleKind kind();

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
	 * source position, the last entry is the destination. Phase-12 ships single-leg
	 * straight-line paths; OSM street-network pathfinding will populate intermediate
	 * waypoints in a later phase without changing this contract.
	 */
	List<LatLon> path();

	/** Game-minute the vehicle was created. Frontend interpolates position from this. */
	long spawnedAtGameMinutes();

	/**
	 * Game-minute the vehicle is expected to arrive (= {@code spawnedAt + pathLength /
	 * metersPerGameMinute}). Stored so the dispatcher and the on-arrival branch agree on
	 * the deadline without recomputing path length.
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
	 * spoilage instead of fulfilment.
	 */
	@Nullable Long spoilageDeadlineGameMinutes();

	/** Casual-biking speed, in metres per game-minute. {@link Robot} returns 170. */
	double metersPerGameMinute();
}

