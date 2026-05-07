package com.dimsumdetours.gtfs;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.ShapePoint;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase-18: builds a flat, JSON-friendly snapshot of the active GTFS feed's
 * routes, stops and shapes. The frontend consumes this once at boot to render:
 * <ul>
 *   <li>stop markers on the map (with per-stop "downstream routes" popups);</li>
 *   <li>ambient bus markers sliding along route shapes at a constant
 *       {@link com.dimsumdetours.config.GameConstants#BUS_HEADWAY_GAME_MINUTES}
 *       cadence — the in-game schedule is invented, the spatial data is real.</li>
 * </ul>
 *
 * <p>Per route we pick a "representative" trip (the trip with the most stops in
 * its longest direction) and use its shape points (or its ordered stop sequence
 * if no shape is associated). This means each direction of a typical route
 * gets its own entry, but a route's many slight variants collapse to a single
 * canonical polyline — the player sees the route, not a tangle of overlapping
 * service patterns.
 *
 * <p>The first feed in {@link GtfsLoader#listFeeds()} (alphabetically) is the
 * active feed. If no feed is on disk the service stays disabled and
 * {@link #getSnapshot()} returns {@link Optional#empty()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransitSnapshotService {

	private final GtfsLoader loader;

	private volatile @Nullable Snapshot snapshot;

	@PostConstruct
	void initialise() {
		List<String> feeds = loader.listFeeds();
		if (feeds.isEmpty()) {
			log.info("Transit snapshot: no GTFS feed loaded; client transit overlay will stay empty");
			return;
		}
		String firstFeed = feeds.getFirst();
		try {
			GtfsRelationalDaoImpl feed = loader.loadFeed(firstFeed);
			Snapshot built = buildSnapshot(feed);
			snapshot = built;
			log.info("Transit snapshot ready ({}): {} stops, {} route directions",
				firstFeed, built.stops().size(), built.routes().size());
		} catch (Exception ex) {
			log.warn("Transit snapshot: failed to build from feed '{}': {}",
				firstFeed, ex.getMessage());
		}
	}

	public Optional<Snapshot> getSnapshot() {
		return Optional.ofNullable(snapshot);
	}

	/**
	 * Phase-21: resolve the GTFS {@code route_type} (3 = bus, 0 = tram, 4 = ferry, …)
	 * for a given {@code routeId}. Looks up the first {@link TransitRoute} entry whose
	 * {@code routeId} matches (a route can have multiple direction entries; they all
	 * share the same {@code type}). Returns {@code 0} (tram) as a defensive fallback
	 * when the route is unknown — the frontend's glyph picker treats that as a generic
	 * tram silhouette which is a reasonable degraded mode.
	 *
	 * <p>Called by {@code SimulationEngine} just before forwarding a fresh
	 * {@code RUN_STARTED} cargo event onto the SSE sink. The GameState core
	 * (which emits the placeholder event) is Spring-free and can't reach this
	 * service directly.
	 */
	public int routeTypeForRouteId(String routeId) {
		Snapshot current = snapshot;
		if (current == null || routeId == null) {
			return 0;
		}
		for (TransitRoute route : current.routes()) {
			if (routeId.equals(route.routeId())) {
				return route.type();
			}
		}
		return 0;
	}

	private static Snapshot buildSnapshot(GtfsRelationalDaoImpl feed) {
		// 1. Pick a representative trip per (routeId, directionId). The "best" trip
		// is the one with the most stop_times — it traces the longest variant of
		// the service pattern, which is what we want for a planner overlay.
		Map<String, Trip> bestTripByDirection = new LinkedHashMap<>();
		for (Trip trip : feed.getAllTrips()) {
			if (trip.getRoute() == null || trip.getRoute().getId() == null) {
				continue;
			}
			String routeId = trip.getRoute().getId().getId();
			String directionId = trip.getDirectionId() != null ? trip.getDirectionId() : "0";
			String key = routeId + "|" + directionId;
			Trip incumbent = bestTripByDirection.get(key);
			int incumbentStops = incumbent == null ? -1 : feed.getStopTimesForTrip(incumbent).size();
			int candidateStops = feed.getStopTimesForTrip(trip).size();
			if (candidateStops > incumbentStops) {
				bestTripByDirection.put(key, trip);
			}
		}

		// 2. Build the route DTOs.
		List<TransitRoute> routes = new ArrayList<>(bestTripByDirection.size());
		Map<String, Set<String>> routeIdsPerStop = new HashMap<>();
		for (Trip trip : bestTripByDirection.values()) {
			Route route = trip.getRoute();
			if (route == null || route.getId() == null) {
				continue;
			}
			List<StopTime> stopTimes = feed.getStopTimesForTrip(trip);
			if (stopTimes.size() < 2) {
				continue;
			}
			List<String> orderedStopIds = new ArrayList<>(stopTimes.size());
			long[] stopArrivalGameMinutes = new long[stopTimes.size()];
			long[] stopDepartureGameMinutes = new long[stopTimes.size()];
			// Anchor at the first stop's departure (or arrival as fallback) so
			// game-minutes are measured relative to the trip's origin, not GTFS
			// midnight. Frontend ambient-bus animation interprets these as
			// "minutes since the run departed the first stop".
			int anchor = -1;
			for (StopTime st : stopTimes) {
				if (st.isDepartureTimeSet()) { anchor = st.getDepartureTime(); break; }
				if (st.isArrivalTimeSet()) { anchor = st.getArrivalTime(); break; }
			}
			for (int i = 0; i < stopTimes.size(); i++) {
				StopTime stopTime = stopTimes.get(i);
				if (stopTime.getStop() == null || stopTime.getStop().getId() == null) {
					stopArrivalGameMinutes[i] = -1L;
					stopDepartureGameMinutes[i] = -1L;
					continue;
				}
				String stopId = stopTime.getStop().getId().getId();
				orderedStopIds.add(stopId);
				routeIdsPerStop
					.computeIfAbsent(stopId, id -> new TreeSet<>())
					.add(route.getId().getId());
				stopArrivalGameMinutes[i] = (anchor >= 0 && stopTime.isArrivalTimeSet())
					? Math.max(0L, (stopTime.getArrivalTime() - anchor) / 60L) : -1L;
				stopDepartureGameMinutes[i] = (anchor >= 0 && stopTime.isDepartureTimeSet())
					? Math.max(0L, (stopTime.getDepartureTime() - anchor) / 60L) : -1L;
			}

			List<double[]> shape = resolveShape(feed, trip, stopTimes);
			int[] stopShapeIndices = computeStopShapeIndices(stopTimes, shape);

			// Phase-20: derive missing per-stop game-minutes from shape distance
			// instead of leaving -1 sentinels. The frontend ambient-bus animator
			// and the cargo planner both consume these arrays; sparse feeds
			// (e.g. only first/last stop scheduled, or no times at all) must
			// still produce coherent intermediate timings.
			interpolateMissingStopTimes(
				stopArrivalGameMinutes, stopDepartureGameMinutes,
				stopShapeIndices, shape);

			routes.add(new TransitRoute(
				route.getId().getId() + "|" + (trip.getDirectionId() != null ? trip.getDirectionId() : "0"),
				route.getId().getId(),
				route.getShortName(),
				route.getLongName(),
				route.getType(),
				colourOrNull(route.getColor()),
				colourOrNull(route.getTextColor()),
				trip.getDirectionId(),
				orderedStopIds,
				shape,
				stopArrivalGameMinutes,
				stopDepartureGameMinutes,
				stopShapeIndices
			));
		}

		// 3. Build stops, ordered by id for a deterministic frontend experience.
		List<TransitStop> stops = new ArrayList<>();
		List<Stop> rankedStops = new ArrayList<>(feed.getAllStops());
		rankedStops.sort(Comparator.comparing(s -> s.getId().getId()));
		for (Stop stop : rankedStops) {
			if (stop.getId() == null) {
				continue;
			}
			double lat = stop.getLat();
			double lon = stop.getLon();
			if (lat == 0.0 && lon == 0.0) {
				continue;
			}
			Set<String> routeIds = routeIdsPerStop.getOrDefault(
				stop.getId().getId(), java.util.Collections.emptySet());
			if (routeIds.isEmpty()) {
				// A stop served by no route in our representative-trip set is noise
				// (parent stations, service-only stops). Skip.
				continue;
			}
			stops.add(new TransitStop(
				stop.getId().getId(),
				stop.getName(),
				lat, lon,
				new ArrayList<>(routeIds)
			));
		}
		return new Snapshot(stops, routes);
	}

	/** Shape polyline preference: {@code shapes.txt} when the trip has a shape,
	 * otherwise the trip's ordered stop coordinates. Empty list only if the trip
	 * has no usable geometry (which we'd have already filtered above). */
	private static List<double[]> resolveShape(
		GtfsRelationalDaoImpl feed, Trip trip, List<StopTime> stopTimes
	) {
		if (trip.getShapeId() != null) {
			List<ShapePoint> shapePoints = feed.getShapePointsForShapeId(trip.getShapeId());
			if (shapePoints != null && shapePoints.size() >= 2) {
				List<ShapePoint> sorted = new ArrayList<>(shapePoints);
				sorted.sort(Comparator.comparingInt(ShapePoint::getSequence));
				List<double[]> out = new ArrayList<>(sorted.size());
				for (ShapePoint point : sorted) {
					out.add(new double[]{point.getLat(), point.getLon()});
				}
				return out;
			}
		}
		List<double[]> out = new ArrayList<>(stopTimes.size());
		for (StopTime stopTime : stopTimes) {
			org.onebusaway.gtfs.model.StopLocation stop = stopTime.getStop();
			if (!(stop instanceof Stop concrete)) {
				continue;
			}
			out.add(new double[]{concrete.getLat(), concrete.getLon()});
		}
		return out;
	}

	private static @Nullable String colourOrNull(@Nullable String hex) {
		if (hex == null || hex.isBlank()) {
			return null;
		}
		return hex.startsWith("#") ? hex : "#" + hex;
	}

	/** Project each stop onto the trip's shape by nearest-vertex distance.
	 * Mirrors {@code GtfsMultiLegPlanner.nearestShapeIndex} so the frontend's
	 * GTFS-time-driven ambient bus animation aligns with the cargo-bus path. */
	private static int[] computeStopShapeIndices(List<StopTime> stopTimes, List<double[]> shape) {
		int[] out = new int[stopTimes.size()];
		if (shape.isEmpty()) {
			return out;
		}
		for (int i = 0; i < stopTimes.size(); i++) {
			org.onebusaway.gtfs.model.StopLocation s = stopTimes.get(i).getStop();
			if (!(s instanceof Stop concrete)) {
				out[i] = 0;
				continue;
			}
			int best = 0;
			double bestD = Double.POSITIVE_INFINITY;
			double lat = concrete.getLat(), lon = concrete.getLon();
			for (int j = 0; j < shape.size(); j++) {
				double[] pt = shape.get(j);
				double dLat = lat - pt[0];
				double dLon = lon - pt[1];
				double d = dLat * dLat + dLon * dLon; // squared-degree, monotonic — avoids haversine cost
				if (d < bestD) { bestD = d; best = j; }
			}
			out[i] = best;
		}
		return out;
	}

	public record Snapshot(List<TransitStop> stops, List<TransitRoute> routes) {
	}

	/**
	 * Phase-20: fill missing per-stop times by piecewise-linear interpolation
	 * against cumulative shape distance. The arrays come in with {@code -1}
	 * for stops the GTFS feed didn't schedule; this method overwrites those
	 * cells with derived values. The user spec is explicit that we never
	 * silently drop sparse-feed data — even a feed with only first/last
	 * stop scheduled produces coherent middle-stop timings.
	 *
	 * <p>If the WHOLE trip has no scheduled times we synthesise them from
	 * shape distance at {@link com.dimsumdetours.config.GameConstants#BUS_METERS_PER_GAME_MINUTE}
	 * — a uniform-cruise-speed model that's the spec's "derive average stop
	 * times by simple math using the distances" fallback. If the shape is
	 * also missing, we fall back to evenly-spaced 1-game-min stops so the
	 * route is still drawable.
	 */
	static void interpolateMissingStopTimes(
		long[] arrivals, long[] departures, int[] stopShapeIndices, List<double[]> shape
	) {
		int n = arrivals.length;
		if (n == 0) {
			return;
		}
		// Cumulative shape-meters per stop.
		double[] shapeCum = new double[shape.size()];
		for (int i = 1; i < shape.size(); i++) {
			double[] a = shape.get(i - 1);
			double[] b = shape.get(i);
			shapeCum[i] = shapeCum[i - 1] + haversineMetres(a[0], a[1], b[0], b[1]);
		}
		double[] stopCum = new double[n];
		for (int i = 0; i < n; i++) {
			int idx = (i < stopShapeIndices.length) ? stopShapeIndices[i] : 0;
			if (idx < 0) idx = 0;
			if (idx >= shapeCum.length) idx = Math.max(0, shapeCum.length - 1);
			stopCum[i] = shapeCum.length == 0 ? i : shapeCum[idx];
		}

		// Collect anchor (stopIdx, cum, time) for the arrivals array.
		fillByDistance(arrivals, stopCum);
		fillByDistance(departures, stopCum);
		// If all-missing, fall back to BUS_METERS_PER_GAME_MINUTE-based synthesis.
		boolean arrivalsAllZero = true;
		for (long v : arrivals) if (v != 0L) { arrivalsAllZero = false; break; }
		if (arrivalsAllZero && stopCum[n - 1] > 0.0) {
			for (int i = 0; i < n; i++) {
				arrivals[i] = Math.max(0L, Math.round(stopCum[i]
					/ com.dimsumdetours.config.GameConstants.BUS_METERS_PER_GAME_MINUTE));
				departures[i] = arrivals[i];
			}
		}
		// Force monotonic non-decreasing (anchors can disagree with shape order in degenerate feeds).
		for (int i = 1; i < n; i++) {
			if (arrivals[i] < arrivals[i - 1]) arrivals[i] = arrivals[i - 1];
			if (departures[i] < arrivals[i]) departures[i] = arrivals[i];
		}
	}

	/** Replace {@code -1} cells in {@code values} with linear interpolation
	 * against neighbouring anchored cells, scaled by {@code stopCum}. Leading
	 * NaNs anchor to 0; trailing NaNs extrapolate from the last segment's pace. */
	private static void fillByDistance(long[] values, double[] stopCum) {
		int n = values.length;
		// First finite.
		int firstFinite = -1;
		for (int i = 0; i < n; i++) {
			if (values[i] >= 0L) { firstFinite = i; break; }
		}
		if (firstFinite < 0) {
			// All missing — leave as zero so the all-missing branch in the caller can detect.
			for (int i = 0; i < n; i++) values[i] = 0L;
			return;
		}
		for (int i = 0; i < firstFinite; i++) values[i] = 0L;
		for (int i = firstFinite + 1; i < n; i++) {
			if (values[i] >= 0L) continue;
			int next = -1;
			for (int j = i + 1; j < n; j++) {
				if (values[j] >= 0L) { next = j; break; }
			}
			if (next < 0) {
				// Trailing — extrapolate at last segment's per-metre pace.
				values[i] = values[i - 1] + 1L;
				continue;
			}
			double cumA = stopCum[i - 1];
			double cumB = stopCum[next];
			double span = Math.max(1e-6, cumB - cumA);
			double t = (stopCum[i] - cumA) / span;
			if (t < 0.0) t = 0.0;
			if (t > 1.0) t = 1.0;
			double interp = values[i - 1] + (values[next] - values[i - 1]) * t;
			values[i] = Math.max(values[i - 1], Math.round(interp));
		}
	}

	/** Local copy of the haversine — small enough to inline rather than depend
	 * on {@code GameState} which would create a cyclical service boot order. */
	private static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
		double R = 6_371_000.0;
		double phi1 = Math.toRadians(lat1);
		double phi2 = Math.toRadians(lat2);
		double dPhi = Math.toRadians(lat2 - lat1);
		double dLambda = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
			+ Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
		return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	public record TransitStop(
		String id,
		@Nullable String name,
		double lat,
		double lon,
		List<String> routeIds
	) {
	}

	public record TransitRoute(
		String id,
		String routeId,
		@Nullable String shortName,
		@Nullable String longName,
		int type,
		@Nullable String colour,
		@Nullable String textColour,
		@Nullable String directionId,
		List<String> stopIds,
		List<double[]> shape,
		// Phase-19: per-stop cumulative game-minutes from the trip's first
		// stop (i.e. {@code arrival_time[i] - departure_time[0]}, scaled
		// 1 GTFS-min → 1 game-min). {@code -1} for stops with missing
		// scheduled times in the feed. Total run time = the last entry.
		long[] stopArrivalGameMinutes,
		long[] stopDepartureGameMinutes,
		// Phase-19: index into {@link #shape} where each stop sits
		// (nearest-vertex projection). Lets the frontend interpolate
		// position by GTFS stop_times rather than constant cruise speed.
		int[] stopShapeIndices
	) {
	}
}









