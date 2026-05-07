package com.dimsumdetours.sim.model.vehicle;

import java.util.UUID;

/**
 * Phase-21 stub: a {@link CargoManifest} that has reached its boarding
 * stop and is queued for the next ambient run on its planned route. The
 * boarding state machine groups these by
 * {@code (boardingStopId, routeId)} and drains them onto a
 * {@link TransitVehicle} the moment the next run crosses the stop in
 * the current tick window.
 *
 * <p>No callers yet.
 */
public record WaitingCargo(
	UUID id,
	String routeId,
	String boardingStopId,
	CargoManifest manifest,
	long arrivedAtStopGameMinutes
) {

	public WaitingCargo {
		if (routeId == null || routeId.isBlank()
			|| boardingStopId == null || boardingStopId.isBlank()) {
			throw new IllegalArgumentException(
				"WaitingCargo routeId/boardingStopId must be non-blank");
		}
		if (manifest == null) {
			throw new IllegalArgumentException("WaitingCargo manifest must be non-null");
		}
	}
}

