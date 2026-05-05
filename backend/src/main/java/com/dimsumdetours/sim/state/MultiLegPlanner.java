package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.LatLon;

import java.util.Optional;

/**
 * Phase-16 SPI: build a multi-leg shipment chain (robot → bus → robot, etc.) for
 * source → destination pairs that lie beyond {@link
 * com.dimsumdetours.config.GameConstants#MAX_ROBOT_LEG_METERS} a single robot can
 * cover. Lives in framework-agnostic {@code sim/} so {@link GameState} stays
 * Spring-free; the Spring side wires in {@code GtfsMultiLegPlanner} which
 * consults a GTFS feed for the bus middle leg.
 *
 * <p>Returning {@link Optional#empty()} means "no plan available right now"
 * (no usable transit between these two points, or no GTFS feed is loaded). The
 * dispatcher treats that the same as "no producer in range" and skips the
 * shipment until the next tick.
 *
 * <p>The default {@link #disabled()} implementation always returns empty, which
 * is the correct fallback for unit tests + headless integrations without GTFS
 * data on disk.
 */
@FunctionalInterface
public interface MultiLegPlanner {

	/**
	 * Plan a chain from {@code source} to {@code destination} departing at
	 * approximately {@code departureGameMinutes}. Implementations may snap to
	 * the next scheduled departure on the chosen route; the returned
	 * {@link VehicleChain} encodes the actual durations the engine should use.
	 */
	Optional<VehicleChain> plan(
		LatLon source,
		LatLon destination,
		long departureGameMinutes
	);

	/**
	 * Default planner that never produces a chain. Used as the safe baseline
	 * before Spring has wired in the GTFS-aware implementation, and in tests
	 * that exercise the single-leg path only.
	 */
	static MultiLegPlanner disabled() {
		return (source, destination, departureGameMinutes) -> Optional.empty();
	}
}

