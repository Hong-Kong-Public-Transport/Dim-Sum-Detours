package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Phase-14 SPI: turn an {@code (origin, destination)} pair into a list of path
 * waypoints the vehicle will visit in order. Lives in the framework-agnostic
 * {@code sim/} package so {@link GameState#spawnRobot} can call it without
 * pulling in a Spring dependency. The Spring side's
 * {@code com.dimsumdetours.osm.routing.OsmRouter} implements this against the
 * OSM street graph; tests inject the trivial straight-line {@link #straightLine()}.
 *
 * <p>Implementations return a non-empty list whose first point is {@code source},
 * last point is {@code destination}, and every intermediate point is finite and
 * in-range — or {@code null} when no path can be found (graph not loaded, leg
 * over the {@code MAX_ROBOT_LEG_METERS} cap, endpoints too far from any street
 * node, A* expansion budget exhausted). The simulation core treats {@code null}
 * as "skip dispatch this tick" — robots <strong>never</strong> phase straight-
 * line through buildings as a silent fallback any more.
 */
@FunctionalInterface
public interface RouteProvider {

	/**
	 * Compute path waypoints from {@code source} to {@code destination}, inclusive.
	 * Returns {@code null} when no path is available; the caller is expected to
	 * skip the dispatch (or try a multi-leg plan) rather than guess a straight line.
	 */
	@Nullable List<LatLon> findPath(LatLon source, LatLon destination);

	/**
	 * Trivial provider that returns the straight-line two-point path. Used as the
	 * default when no Spring router is wired (unit tests, embedded integrations).
	 */
	static RouteProvider straightLine() {
		return (source, destination) -> List.of(source, destination);
	}
}

