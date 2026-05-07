package com.dimsumdetours.osm.routing;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.gtfs.BoundingBox;
import com.dimsumdetours.gtfs.GtfsLoader;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.RouteProvider;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase-14: A* pathfinder over the OSM street graph. Implements
 * {@link RouteProvider} so it can be slotted into {@link GameState#setRouteProvider}.
 *
 * <p>Loading happens once asynchronously at boot — until the graph arrives the router
 * returns {@code null} from {@link #findPath} and the dispatcher simply skips that
 * spawn until the graph publishes. A* runs on the simulation thread; the haversine
 * heuristic is admissible (true ground-distance lower bound) so the resulting paths
 * are optimal in metres.
 *
 * <p>Failure modes that return {@code null} (no path → caller skips dispatch):
 * <ul>
 *   <li>Graph not yet loaded.</li>
 *   <li>Source-to-destination straight-line distance exceeds
 *       {@link GameConstants#MAX_ROBOT_LEG_METERS}.</li>
 *   <li>Source or destination further than
 *       {@link GameConstants#OSM_MAX_SNAP_METERS} from any street node (the
 *       farm/factory was placed deep inside a country park, etc).</li>
 *   <li>A* exhausts {@link GameConstants#OSM_MAX_ASTAR_EXPANSIONS} without reaching
 *       the goal (disconnected components, malformed data).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OsmRouter implements RouteProvider {

	private final OsmHighwayLoader loader;
	private final GameState gameState;
	private final GtfsLoader gtfsLoader;

	/** Atomic so the simulation thread sees a fully-built graph the moment the
	 * loader publishes it; until then reads return {@link OsmStreetGraph#EMPTY}. */
	private final AtomicReference<OsmStreetGraph> graphRef = new AtomicReference<>(OsmStreetGraph.EMPTY);

	// Phase-16 diagnostics: count fallback reasons + log a periodic summary so a
	// player who sees straight-line robots in the wild can spot WHY in the logs.
	private final AtomicLong astarRoutes = new AtomicLong();
	private final AtomicLong fallbackGraphEmpty = new AtomicLong();
	private final AtomicLong fallbackOverCap = new AtomicLong();
	private final AtomicLong fallbackSnap = new AtomicLong();
	private final AtomicLong fallbackAstarMiss = new AtomicLong();
	private final AtomicLong lastReportedRoutes = new AtomicLong();

	/** Async graph-load worker handle; tracked so {@link #stop()} can dispose it on
	 *  context close. Without this, a Spring context shutdown mid-load orphans the
	 *  Overpass HTTP call on a bounded-elastic thread. */
	private @org.jspecify.annotations.Nullable Disposable graphLoadSubscription;

	@PostConstruct
	void start() {
		// Wire ourselves into the simulation core; until the graph loads we delegate
		// to straight-line via the early-return in findPath().
		gameState.setRouteProvider(this);
		// Async load on a bounded-elastic worker so Spring boot doesn't block on Overpass.
		graphLoadSubscription = Schedulers.boundedElastic().schedule(() -> {
			try {
				long startMs = System.currentTimeMillis();
				// Phase-17: align the street graph with the active GTFS feed's footprint
				// so buildings (placed in the GTFS-feed-derived placement zones) actually
				// have streets nearby. Falls back to the static config bbox when no feed
				// is loaded — keeps tests / first-run behaviour intact.
				OsmStreetGraph graph = loadGraphAlignedToActiveFeed();
				graphRef.set(graph);
				long elapsed = System.currentTimeMillis() - startMs;
				if (graph.nodeCount() == 0) {
					log.warn("OSM router: graph is EMPTY after {} ms — every robot will use straight-line paths. "
						+ "Check Overpass connectivity, world-bbox config, and data/osm/ cache file.",
						elapsed);
				} else {
					log.info("OSM router: graph ready in {} ms — {} nodes. A* enabled for ≤ {} m trips.",
						elapsed, graph.nodeCount(), (long) GameConstants.MAX_ROBOT_LEG_METERS);
				}
			} catch (RuntimeException ex) {
				log.error("OSM router: graph load threw; staying on straight-line fallback", ex);
			}
		});
	}

	@PreDestroy
	void stop() {
		if (graphLoadSubscription != null) {
			graphLoadSubscription.dispose();
			graphLoadSubscription = null;
		}
	}

	/**
	 * Compute the bbox the highway graph should cover. Priority:
	 * <ol>
	 *   <li>Active GTFS feed's stop-derived bbox (the same bbox the OsmController uses
	 *       to fetch placement zones — keeps placements and streets in lockstep).</li>
	 *   <li>Static {@code dimsumdetours.world-bbox} from {@code application.yml} as a fallback.</li>
	 * </ol>
	 * The feed bbox is padded slightly so streets just outside the GTFS footprint
	 * are still part of the graph (a stop on the very edge needs an outbound road
	 * to be routable).
	 */
	private OsmStreetGraph loadGraphAlignedToActiveFeed() {
		var feeds = gtfsLoader.listFeeds();
		if (feeds.isEmpty()) {
			log.info("OSM router: no GTFS feeds; falling back to static world-bbox config");
			return loader.loadWorldGraph();
		}
		String firstFeed = feeds.getFirst();
		try {
			BoundingBox bbox = gtfsLoader.computeBoundingBox(firstFeed);
			double pad = 0.02; // ≈ 2 km of latitude
			double south = bbox.south() - pad;
			double west = bbox.west() - pad;
			double north = bbox.north() + pad;
			double east = bbox.east() + pad;
			log.info("OSM router: aligning graph to GTFS feed '{}' bbox {},{} → {},{} (padded {}°)",
				firstFeed, south, west, north, east, pad);
			return loader.loadGraphForBbox(south, west, north, east);
		} catch (Exception ex) {
			log.warn("OSM router: failed to derive bbox from feed '{}'; falling back to static config: {}",
				firstFeed, ex.getMessage());
			return loader.loadWorldGraph();
		}
	}

	@Override
	public @org.jspecify.annotations.Nullable List<LatLon> findPath(LatLon source, LatLon destination) {
		double straightLineMeters = GameState.haversineMetres(
			source.lat(), source.lon(), destination.lat(), destination.lon());
		if (straightLineMeters > GameConstants.MAX_ROBOT_LEG_METERS) {
			noteFallback("over-cap", fallbackOverCap, () ->
				"OSM no-path: trip is " + (long) straightLineMeters
					+ " m, exceeds MAX_ROBOT_LEG_METERS "
					+ (long) GameConstants.MAX_ROBOT_LEG_METERS + " m — dispatch skipped");
			maybeReport();
			return null;
		}
		OsmStreetGraph graph = graphRef.get();
		if (graph.nodeCount() == 0) {
			noteFallback("graph-empty", fallbackGraphEmpty, () ->
				"OSM no-path: graph not yet loaded — dispatch skipped");
			maybeReport();
			return null;
		}
		int sourceIdx = graph.nearestNode(source.lat(), source.lon());
		int destIdx = graph.nearestNode(destination.lat(), destination.lon());
		if (sourceIdx < 0 || destIdx < 0) {
			noteFallback("snap-miss", fallbackSnap, () ->
				"OSM no-path: no candidate node within snap radius — dispatch skipped");
			maybeReport();
			return null;
		}
		double snapSource = graph.nearestNodeDistance(source.lat(), source.lon(), sourceIdx);
		double snapDest = graph.nearestNodeDistance(destination.lat(), destination.lon(), destIdx);
		if (snapSource > GameConstants.OSM_MAX_SNAP_METERS
			|| snapDest > GameConstants.OSM_MAX_SNAP_METERS) {
			noteFallback("snap-too-far", fallbackSnap, () ->
				"OSM no-path: building too far from any street — source snap "
					+ (long) snapSource + " m, dest snap " + (long) snapDest
					+ " m, cap " + (long) GameConstants.OSM_MAX_SNAP_METERS
					+ " m — dispatch skipped");
			maybeReport();
			return null;
		}
		if (sourceIdx == destIdx) {
			// Both endpoints snap to the same street node — A* would return a
			// zero-length node path; emit the two-point trip directly. This is
			// not a "fallback to straight line" because the graph confirmed the
			// endpoints are co-located on the same routable node.
			astarRoutes.incrementAndGet();
			maybeReport();
			return List.of(source, destination);
		}
		IntArrayList nodePath = aStar(graph, sourceIdx, destIdx);
		if (nodePath == null) {
			noteFallback("astar-miss", fallbackAstarMiss, () ->
				"OSM no-path: A* exhausted " + GameConstants.OSM_MAX_ASTAR_EXPANSIONS
					+ " expansions — likely disconnected components — dispatch skipped");
			maybeReport();
			return null;
		}
		List<LatLon> waypoints = new ArrayList<>(nodePath.size() + 2);
		waypoints.add(source);
		for (int i = 0; i < nodePath.size(); i++) {
			int nodeIdx = nodePath.getInt(i);
			waypoints.add(new LatLon(graph.nodeLat(nodeIdx), graph.nodeLon(nodeIdx)));
		}
		waypoints.add(destination);
		long routes = astarRoutes.incrementAndGet();
		if (routes == 1) {
			log.info("OSM router: first A* route succeeded ({} waypoints, {} m direct)",
				waypoints.size(), (long) straightLineMeters);
		}
		maybeReport();
		return waypoints;
	}

	/** Counts a no-path event and emits an INFO log for the first {@link #FALLBACK_LOG_LIMIT_PER_REASON}
	 * occurrences of each reason so the operator can SEE why dispatch is being skipped
	 * without flipping to DEBUG and being drowned in noise. */
	private void noteFallback(String reason, AtomicLong counter, java.util.function.Supplier<String> message) {
		long count = counter.incrementAndGet();
		if (count <= FALLBACK_LOG_LIMIT_PER_REASON) {
			log.info("[{}/{}] {}", reason, count, message.get());
		}
	}

	private static final long FALLBACK_LOG_LIMIT_PER_REASON = 5L;

	/**
	 * Periodic summary log so straight-line fallbacks aren't invisible. Logged at
	 * every {@code REPORT_INTERVAL} A* completions; counters are running totals
	 * so the deltas in successive lines tell the operator the recent rate.
	 */
	private static final long REPORT_INTERVAL = 100L;

	private void maybeReport() {
		// Phase-17: gate on TOTAL decisions, not just successful A* routes —
		// otherwise a 100% fallback session never logs and the operator can't
		// tell the router is misbehaving.
		long routes = astarRoutes.get();
		long total = routes + fallbackGraphEmpty.get() + fallbackOverCap.get()
			+ fallbackSnap.get() + fallbackAstarMiss.get();
		long lastReported = lastReportedRoutes.get();
		if (total - lastReported < REPORT_INTERVAL) {
			return;
		}
		if (!lastReportedRoutes.compareAndSet(lastReported, total)) {
			return;
		}
		log.info("OSM router: {} A* routes; no-path skips: graph-empty={}, over-cap={}, snap={}, astar-miss={}",
			routes,
			fallbackGraphEmpty.get(),
			fallbackOverCap.get(),
			fallbackSnap.get(),
			fallbackAstarMiss.get());
	}


	/**
	 * Standard A* with a haversine heuristic. Returns the list of node indices from
	 * {@code sourceIdx} to {@code destIdx} inclusive, or {@code null} if no path
	 * exists within {@link GameConstants#OSM_MAX_ASTAR_EXPANSIONS} expansions.
	 *
	 * <p>Uses primitive {@code int} node indices throughout — fastutil's {@code Int*}
	 * collections keep the priority queue and visited-set free of boxing overhead.
	 */
	private static @org.jspecify.annotations.Nullable IntArrayList aStar(
		OsmStreetGraph graph, int sourceIdx, int destIdx
	) {
		int nodeCount = graph.nodeCount();
		double destLat = graph.nodeLat(destIdx);
		double destLon = graph.nodeLon(destIdx);

		double[] gScore = new double[nodeCount];
		int[] cameFrom = new int[nodeCount];
		java.util.Arrays.fill(gScore, Double.POSITIVE_INFINITY);
		java.util.Arrays.fill(cameFrom, -1);
		gScore[sourceIdx] = 0.0;

		PriorityQueue<long[]> open = new PriorityQueue<>((a, b) ->
			Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
		open.add(packEntry(heuristic(graph, sourceIdx, destLat, destLon), sourceIdx));

		int expansions = 0;
		while (!open.isEmpty()) {
			if (++expansions > GameConstants.OSM_MAX_ASTAR_EXPANSIONS) {
				return null;
			}
			long[] entry = open.poll();
			int current = (int) entry[1];
			if (current == destIdx) {
				return reconstruct(cameFrom, current);
			}
			double currentG = gScore[current];
			int edgeStart = graph.edgeStart(current);
			int edgeEnd = graph.edgeEnd(current);
			for (int e = edgeStart; e < edgeEnd; e++) {
				int neighbour = graph.edgeTarget(e);
				double tentativeG = currentG + graph.edgeLength(e);
				if (tentativeG < gScore[neighbour]) {
					gScore[neighbour] = tentativeG;
					cameFrom[neighbour] = current;
					double f = tentativeG + heuristic(graph, neighbour, destLat, destLon);
					open.add(packEntry(f, neighbour));
				}
			}
		}
		return null;
	}

	private static double heuristic(OsmStreetGraph graph, int nodeIdx, double destLat, double destLon) {
		return GameState.haversineMetres(
			graph.nodeLat(nodeIdx), graph.nodeLon(nodeIdx), destLat, destLon);
	}

	/** Pack {@code (fScore, nodeIdx)} into two longs so the PQ avoids object allocation. */
	private static long[] packEntry(double fScore, int nodeIdx) {
		return new long[]{Double.doubleToLongBits(fScore), nodeIdx};
	}

	private static IntArrayList reconstruct(int[] cameFrom, int destIdx) {
		ObjectArrayList<Integer> reverse = new ObjectArrayList<>();
		int cursor = destIdx;
		while (cursor >= 0) {
			reverse.add(cursor);
			cursor = cameFrom[cursor];
		}
		IntArrayList path = new IntArrayList(reverse.size());
		for (int i = reverse.size() - 1; i >= 0; i--) {
			path.add(reverse.get(i).intValue());
		}
		return path;
	}


	/** Test seam: inject a pre-built graph (e.g. a hand-rolled fixture). */
	void setGraphForTesting(OsmStreetGraph graph) {
		graphRef.set(graph);
	}
}

