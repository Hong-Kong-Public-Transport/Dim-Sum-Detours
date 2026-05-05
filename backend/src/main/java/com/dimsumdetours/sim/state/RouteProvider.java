package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.LatLon;

import java.util.List;

/**
 * Phase-14 SPI: turn an {@code (origin, destination)} pair into a list of path
 * waypoints the vehicle will visit in order. Lives in the framework-agnostic
 * {@code sim/} package so {@link GameState#spawnRobot} can call it without
 * pulling in a Spring dependency. The Spring side's
 * {@code com.dimsumdetours.osm.routing.OsmRouter} implements this against the
 * OSM street graph; tests inject the trivial straight-line {@link #straightLine()}.
 *
 * <p>Implementations <strong>must</strong> return a non-empty list whose first
 * point is {@code source}, last point is {@code destination}, and every
 * intermediate point is finite and in-range. A pathfinder that fails to find a
 * route should return the straight-line two-point fallback rather than throw —
 * the simulation has no meaningful "vehicle can't move" state today.
 */
@FunctionalInterface
public interface RouteProvider {

	/** Compute path waypoints from {@code source} to {@code destination}, inclusive. */
	List<LatLon> findPath(LatLon source, LatLon destination);

	/**
	 * Trivial provider that returns the straight-line two-point path. Used as the
	 * default when no Spring router is wired (unit tests, embedded integrations).
	 */
	static RouteProvider straightLine() {
		return (source, destination) -> List.of(source, destination);
	}
}

