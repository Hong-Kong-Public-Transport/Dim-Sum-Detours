package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-21 stub: a single shipment of cargo riding (or about to ride) a
 * {@link TransitRunId transit run}. Carries everything the dispatcher /
 * boarding state machine needs to move the cargo end-to-end without
 * consulting external state:
 *
 * <ul>
 *   <li>where the cargo came from + where it's going (building ids),</li>
 *   <li>the boarding + alighting stops on the planned transit leg,</li>
 *   <li>the cargo itself,</li>
 *   <li>optional order id + spoilage deadline propagated from the
 *       producing dispatch,</li>
 *   <li>the post-transit OSM path the connecting robot will walk after
 *       alighting, plus its precomputed duration in game-minutes.</li>
 * </ul>
 *
 * <p>No callers yet — wired into the dispatcher and
 * {@code GameState.advanceVehicles} state machine in Phase 21.
 */
public record CargoManifest(
	UUID id,
	UUID sourceBuildingId,
	UUID destinationBuildingId,
	String boardingStopId,
	String alightingStopId,
	Map<String, Integer> cargo,
	@Nullable UUID orderId,
	@Nullable Long spoilageDeadlineGameMinutes,
	List<LatLon> postTransitPath,
	long postTransitDurationGameMinutes,
	long loadedAtGameMinutes
) {

	public CargoManifest {
		cargo = Map.copyOf(cargo);
		postTransitPath = List.copyOf(postTransitPath);
		if (cargo.isEmpty()) {
			throw new IllegalArgumentException("CargoManifest cargo cannot be empty");
		}
		if (postTransitPath.size() < 2) {
			throw new IllegalArgumentException(
				"CargoManifest postTransitPath must have at least 2 waypoints, got "
					+ postTransitPath.size());
		}
		if (postTransitDurationGameMinutes < 0L) {
			throw new IllegalArgumentException(
				"CargoManifest postTransitDurationGameMinutes must be non-negative, got "
					+ postTransitDurationGameMinutes);
		}
	}
}

