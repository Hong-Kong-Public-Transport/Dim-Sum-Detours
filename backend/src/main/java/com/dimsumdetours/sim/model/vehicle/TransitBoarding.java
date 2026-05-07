package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;

import java.util.List;

/**
 * Phase-21 stub: the "remaining transit plan" attached to a first-mile
 * robot. Replaces the recursive {@link VehicleHandoff} chain — this
 * record is a flat description of the single allowed transit leg
 * (per the user's spec, no transfers).
 *
 * <p>Stored on a {@link Robot} (in Phase 21,
 * {@code @Nullable TransitBoarding boarding} replaces
 * {@code @Nullable VehicleHandoff handoff}). When the robot reaches the
 * boarding stop, the boarding state machine drains it into a
 * {@link WaitingCargo} keyed by {@code (boardingStopId, routeId)} —
 * the exact departure is resolved at boarding time, never stored on
 * the path.
 *
 * <p>No callers yet.
 */
public record TransitBoarding(
	String routeId,
	String boardingStopId,
	String alightingStopId,
	List<LatLon> postTransitPath,
	long postTransitDurationGameMinutes
) {

	public TransitBoarding {
		if (routeId == null || routeId.isBlank()
			|| boardingStopId == null || boardingStopId.isBlank()
			|| alightingStopId == null || alightingStopId.isBlank()) {
			throw new IllegalArgumentException(
				"TransitBoarding routeId/boardingStopId/alightingStopId must be non-blank");
		}
		postTransitPath = List.copyOf(postTransitPath);
		if (postTransitPath.size() < 2) {
			throw new IllegalArgumentException(
				"TransitBoarding postTransitPath must have at least 2 waypoints");
		}
		if (postTransitDurationGameMinutes < 0L) {
			throw new IllegalArgumentException(
				"TransitBoarding postTransitDurationGameMinutes must be non-negative");
		}
	}
}

