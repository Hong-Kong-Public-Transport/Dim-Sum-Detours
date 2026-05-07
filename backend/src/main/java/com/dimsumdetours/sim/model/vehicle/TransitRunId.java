package com.dimsumdetours.sim.model.vehicle;

/**
 * Phase-21 stub: canonical key shared between an ambient transit sprite
 * and any backend cargo manifest riding the same run.
 *
 * <p>{@code departureOffsetGameMinutes} is the run's <em>scheduled</em>
 * departure as game-minutes since {@code t = 0}, computed from the
 * route's run-time and the headway slot:
 * {@code floor((now − k·H) / runTime) · runTime + k·H} where
 * {@code H = BUS_HEADWAY_GAME_MINUTES = 5}.
 *
 * <p>No callers yet — the recursive {@link VehicleHandoff} model still
 * drives boarding in Phase 20. Wired into the dispatch loop in Phase 21.
 */
public record TransitRunId(String routeId, long departureOffsetGameMinutes) {

	public TransitRunId {
		if (routeId == null || routeId.isBlank()) {
			throw new IllegalArgumentException("routeId must be non-blank");
		}
	}
}

