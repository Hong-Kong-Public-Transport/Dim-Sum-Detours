/**
 * Phase-14 OSM street-network pathfinder. Loads {@code way["highway"]} ways from
 * Overpass for the configured world bbox at startup, builds a CSR-shaped adjacency
 * graph keyed by OSM node id, and serves A* shortest-path queries via the
 * {@link com.dimsumdetours.sim.state.RouteProvider} SPI.
 *
 * <p>Loading is fully asynchronous — the simulation falls back to straight-line
 * paths until the graph is ready, so a slow Overpass response never blocks startup.
 * Disconnected components, malformed data, or cargo whose endpoints sit further
 * than {@link com.dimsumdetours.config.GameConstants#OSM_MAX_SNAP_METERS} from any
 * way also fall back to straight-line rather than throwing.
 */
@NullMarked
package com.dimsumdetours.osm.routing;

import org.jspecify.annotations.NullMarked;

