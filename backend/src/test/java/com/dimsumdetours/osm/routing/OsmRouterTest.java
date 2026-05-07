package com.dimsumdetours.osm.routing;

import com.dimsumdetours.sim.model.LatLon;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-14 OSM router unit tests. Hand-rolls a tiny 4-node grid graph and verifies
 * the router walks the expected nodes; also verifies that no path is returned (the
 * dispatch is meant to be skipped) for a graph-less router and for endpoints too
 * far from any street node — Phase-17 retired the silent straight-line fallback.
 */
class OsmRouterTest {

	/**
	 * Grid layout (lat ascending north, lon ascending east). Edges are ~200 m so
	 * the corner-to-corner trip clears the OsmRouter short-hop early-exit
	 * threshold (skip-A*-when-under-150m) and exercises the actual A* path.
	 * <pre>
	 *   2 ── 3
	 *   │    │
	 *   0 ── 1
	 * </pre>
	 * Edges: 0-1, 0-2, 1-3, 2-3 (bidirectional).
	 */
	private static OsmStreetGraph buildGrid() {
		// 0.002° latitude ≈ 222 m; 0.003° longitude at lat 48 ≈ 223 m.
		DoubleArrayList lats = new DoubleArrayList(new double[]{48.0, 48.0, 48.002, 48.002});
		DoubleArrayList lons = new DoubleArrayList(new double[]{-122.0, -121.997, -122.0, -121.997});
		IntArrayList from = new IntArrayList();
		IntArrayList to = new IntArrayList();
		DoubleArrayList lengths = new DoubleArrayList();
		for (int[] e : new int[][]{{0, 1}, {1, 0}, {0, 2}, {2, 0}, {1, 3}, {3, 1}, {2, 3}, {3, 2}}) {
			from.add(e[0]);
			to.add(e[1]);
			lengths.add(220.0);
		}
		return OsmStreetGraph.build(lats, lons, from, to, lengths);
	}

	@Test
	void noPathReturnedWhenGraphEmpty() {
		OsmRouter router = new OsmRouter(null, null, null);
		// graphRef defaults to EMPTY — findPath should return null so the
		// dispatcher skips the spawn rather than silently straight-lining.
		List<LatLon> path = router.findPath(new LatLon(48.0, -122.0), new LatLon(48.001, -121.999));
		assertNull(path);
	}

	@Test
	void aStarWalksGridFromCornerToCorner() {
		OsmRouter router = new OsmRouter(null, null, null);
		router.setGraphForTesting(buildGrid());
		List<LatLon> path = router.findPath(
			new LatLon(48.0, -122.0),     // node 0
			new LatLon(48.002, -121.997)  // node 3
		);
		// source + at-least-2 graph nodes + destination
		assertNotNull(path);
		assertTrue(path.size() >= 4, "expected source + path + dest, got " + path.size());
		assertEquals(48.0, path.getFirst().lat());
		assertEquals(48.002, path.getLast().lat());
	}

	@Test
	void noPathReturnedWhenSourceTooFarFromAnyNode() {
		OsmRouter router = new OsmRouter(null, null, null);
		router.setGraphForTesting(buildGrid());
		// Several km north of the grid — should exceed the snap threshold AND
		// the 5 km straight-line cap, both of which now return null.
		List<LatLon> path = router.findPath(
			new LatLon(49.0, -122.0),
			new LatLon(48.001, -121.999));
		assertNull(path);
	}
}

