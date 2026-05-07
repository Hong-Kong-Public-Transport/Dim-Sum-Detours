package com.dimsumdetours.engine.routing;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.gtfs.GtfsLoader;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.RoutePlan;
import com.dimsumdetours.sim.state.RouteProvider;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-21 router contract: take a (source, destination) pair and
 * return an explicit {@link RoutePlan} variant — never {@link
 * java.util.Optional}, so the dispatcher pattern-matches the outcome
 * and cannot accidentally treat a {@code NoPath} as "fall through to
 * direct robot".
 *
 * <p>This service owns the entire transit-planning pipeline: it loads
 * the active GTFS feed at boot (via {@link GtfsLoader}), pre-indexes
 * {@code stopId → trips visiting that stop}, and on each {@link #plan}
 * call performs the top-K nearest-stop search + connecting-trip lookup
 * + per-leg OSM routing that the legacy {@code GtfsMultiLegPlanner}
 * used to perform. The legacy planner has been deleted in Phase 21;
 * this class is the single home for transit pathfinding.
 *
 * <p>Pathfinding is <strong>headway-agnostic</strong> — the planner
 * never asks "when's the next bus?" It only computes "where would the
 * cargo board, where would it alight, and which OSM paths flank the
 * transit leg?" The actual boarding minute is resolved at boarding
 * time inside {@code GameState.advanceVehicles} via the
 * {@code TransitSchedule.findRunCrossingStop} SPI; the alighting
 * minute is resolved by {@code TransitSchedule.arrivalAtStop}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Try transit. Search the top-K nearest GTFS stops within
 *       {@link GameConstants#MAX_ROBOT_LEG_METERS} of source + dest;
 *       for each (boarding, alighting) candidate pair find a trip
 *       that visits boarding before alighting; for the first pair
 *       whose two robot legs are both OSM-routable AND ≤ 5 km,
 *       return a {@link RoutePlan.Transit}.</li>
 *   <li>Fall back to direct robot. If
 *       {@code haversine(source, dest) ≤ MAX_ROBOT_LEG_METERS} and
 *       the OSM path itself is also ≤ that cap, return
 *       {@link RoutePlan.DirectRobot}.</li>
 *   <li>Otherwise return {@link RoutePlan.NoPath}. The dispatcher
 *       counts this as a failed dispatch and does not debit
 *       inventory.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutePlanner {

	private final GtfsLoader loader;
	private final RouteProvider routeProvider;

	private volatile @Nullable GtfsRelationalDaoImpl activeFeed;
	private volatile @Nullable Map<String, List<Trip>> tripsByStopId;

	// Phase-17 diagnostics: surface why a plan wasn't produced.
	private final AtomicLong attempts = new AtomicLong();
	private final AtomicLong planSuccess = new AtomicLong();
	private final AtomicLong noFeed = new AtomicLong();
	private final AtomicLong noBoardingStop = new AtomicLong();
	private final AtomicLong noAlightingStop = new AtomicLong();
	private final AtomicLong noConnectingTrip = new AtomicLong();
	private final AtomicLong noOsmRoute = new AtomicLong();
	private static final long FAIL_LOG_LIMIT_PER_REASON = 5L;
	/** Top-K nearest stops on each side. With K=5 we get up to 25 candidate
	 * (boarding, alighting) pairs before falling through. */
	private static final int CANDIDATE_STOPS_PER_SIDE = 5;

	@PostConstruct
	void initialise() {
		List<String> feeds = loader.listFeeds();
		if (feeds.isEmpty()) {
			log.info("RoutePlanner: no GTFS feed loaded; transit branch will return NoPath");
			return;
		}
		String firstFeed = feeds.getFirst();
		try {
			GtfsRelationalDaoImpl feed = loader.loadFeed(firstFeed);
			activeFeed = feed;
			tripsByStopId = buildStopTripsIndex(feed);
			log.info("RoutePlanner active on feed '{}': {} stops, {} trips, {} stop→trip index entries",
				firstFeed,
				feed.getAllStops().size(),
				feed.getAllTrips().size(),
				tripsByStopId.size());
		} catch (Exception ex) {
			log.warn("RoutePlanner: failed to load GTFS feed '{}'; transit branch will return NoPath: {}",
				firstFeed, ex.getMessage());
		}
	}

	/** {@code stopId → trips that visit that stop}. Used by {@link #findConnectingTrip}
	 * to skip every trip that doesn't even touch the boarding stop. */
	private static Map<String, List<Trip>> buildStopTripsIndex(GtfsRelationalDaoImpl feed) {
		Map<String, List<Trip>> index = new HashMap<>();
		for (Trip trip : feed.getAllTrips()) {
			for (StopTime stopTime : feed.getStopTimesForTrip(trip)) {
				org.onebusaway.gtfs.model.StopLocation stop = stopTime.getStop();
				if (stop == null || stop.getId() == null) {
					continue;
				}
				index.computeIfAbsent(stop.getId().getId(), id -> new ArrayList<>()).add(trip);
			}
		}
		return index;
	}

	/**
	 * Build a {@link RoutePlan} from {@code source} to {@code destination}.
	 * Pathfinding ignores headways and wait times — the actual boarding
	 * minute is resolved at boarding time by
	 * {@link com.dimsumdetours.sim.state.GameState#advanceVehicles(long)}.
	 */
	public RoutePlan plan(LatLon source, LatLon destination) {
		attempts.incrementAndGet();
		RoutePlan.@Nullable Transit transit = planTransit(source, destination);
		if (transit != null) {
			return transit;
		}
		double straightLineMetres = GameState.haversineMetres(
			source.lat(), source.lon(), destination.lat(), destination.lon());
		if (straightLineMetres > GameConstants.MAX_ROBOT_LEG_METERS) {
			return RoutePlan.NoPath.INSTANCE;
		}
		List<LatLon> path = routeProvider.findPath(source, destination);
		if (path == null || path.size() < 2) {
			return RoutePlan.NoPath.INSTANCE;
		}
		long pathMetres = (long) Math.ceil(GameState.pathLengthMetres(path));
		if (pathMetres > GameConstants.MAX_ROBOT_LEG_METERS) {
			// OSM detour pushed the routed length over the cap even though
			// the great-circle distance was below — same per-leg gate as
			// the transit-leg fallback below.
			return RoutePlan.NoPath.INSTANCE;
		}
		long durationGameMinutes = Math.max(1L, Math.round(
			pathMetres / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));
		return new RoutePlan.DirectRobot(path, durationGameMinutes);
	}

	/** Phase-21: transit-only planner. Returns {@code null} when no
	 * transit chain is feasible (no feed loaded, no nearby stops, no
	 * connecting trip, or no OSM-routable robot legs on both ends). */
	private RoutePlan.@Nullable Transit planTransit(LatLon source, LatLon destination) {
		GtfsRelationalDaoImpl feed = activeFeed;
		Map<String, List<Trip>> stopTripsIndex = tripsByStopId;
		if (feed == null || stopTripsIndex == null) {
			recordFailure("no-feed", noFeed,
				() -> "no GTFS feed loaded — cannot plan multi-leg trip");
			return null;
		}

		// Top-K nearest stops on each side. Sorted by distance; the first
		// candidate pair with both an OSM-routable robot leg AND a
		// connecting GTFS trip wins.
		List<Stop> boardingCandidates = nearestStops(feed, source.lat(), source.lon(),
			GameConstants.MAX_ROBOT_LEG_METERS, CANDIDATE_STOPS_PER_SIDE);
		if (boardingCandidates.isEmpty()) {
			recordFailure("no-boarding", noBoardingStop, () ->
				"no GTFS stop within " + (long) GameConstants.MAX_ROBOT_LEG_METERS
					+ " m of source " + source.lat() + "," + source.lon());
			return null;
		}
		List<Stop> alightingCandidates = nearestStops(feed, destination.lat(), destination.lon(),
			GameConstants.MAX_ROBOT_LEG_METERS, CANDIDATE_STOPS_PER_SIDE);
		if (alightingCandidates.isEmpty()) {
			recordFailure("no-alighting", noAlightingStop, () ->
				"no GTFS stop within " + (long) GameConstants.MAX_ROBOT_LEG_METERS
					+ " m of destination " + destination.lat() + "," + destination.lon());
			return null;
		}

		boolean sawConnectingTrip = false;
		for (Stop boarding : boardingCandidates) {
			for (Stop alighting : alightingCandidates) {
				if (boarding.getId().equals(alighting.getId())) {
					continue;
				}
				TripMatch match = findConnectingTrip(feed, stopTripsIndex, boarding, alighting);
				if (match == null) {
					continue;
				}
				sawConnectingTrip = true;
				RoutePlan.@Nullable Transit transit = tryBuildTransit(
					source, destination, boarding, alighting, match);
				if (transit != null) {
					long ok = planSuccess.incrementAndGet();
					if (ok == 1L || ok % 10L == 0L) {
						log.info("[transit/plan/ok {}] board {} alight {} via {}",
							ok,
							boarding.getId().getId(),
							alighting.getId().getId(),
							match.tripId);
					}
					return transit;
				}
			}
		}

		if (!sawConnectingTrip) {
			recordFailure("no-trip", noConnectingTrip, () ->
				"no connecting GTFS trip across " + boardingCandidates.size()
					+ "x" + alightingCandidates.size() + " candidate stop pairs");
		} else {
			recordFailure("no-osm-route", noOsmRoute, () ->
				"connecting trips found but none had OSM-routable robot legs on both ends "
					+ "(or one leg exceeded the per-leg cap)");
		}
		return null;
	}

	private RoutePlan.@Nullable Transit tryBuildTransit(
		LatLon source, LatLon destination,
		Stop boarding, Stop alighting,
		TripMatch match
	) {
		LatLon boardingLatLon = new LatLon(boarding.getLat(), boarding.getLon());
		LatLon alightingLatLon = new LatLon(alighting.getLat(), alighting.getLon());

		// Both robot legs MUST resolve to OSM-routed paths AND fit
		// the per-leg cap; otherwise we abandon and try the next
		// candidate pair (the dispatcher will eventually fall back
		// to direct-robot when source-to-destination is itself ≤ cap).
		List<LatLon> firstLegPath = routeProvider.findPath(source, boardingLatLon);
		if (firstLegPath == null || firstLegPath.size() < 2) {
			return null;
		}
		long firstLegMeters = (long) Math.ceil(GameState.pathLengthMetres(firstLegPath));
		if (firstLegMeters > GameConstants.MAX_ROBOT_LEG_METERS) {
			return null;
		}
		long firstLegDuration = Math.max(1L, Math.round(
			firstLegMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));

		List<LatLon> lastLegPath = routeProvider.findPath(alightingLatLon, destination);
		if (lastLegPath == null || lastLegPath.size() < 2) {
			return null;
		}
		long lastLegMeters = (long) Math.ceil(GameState.pathLengthMetres(lastLegPath));
		if (lastLegMeters > GameConstants.MAX_ROBOT_LEG_METERS) {
			return null;
		}
		long lastLegDuration = Math.max(1L, Math.round(
			lastLegMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));

		String routeId = match.routeId;
		if (routeId == null || routeId.isBlank()) {
			// {@link RoutePlan.Transit} demands a non-blank routeId so the
			// frontend's CARGO_LOADED → ambient sprite scaling has
			// something to key off. A trip without a route id can't
			// anchor a transit run, so reject the candidate.
			return null;
		}
		return new RoutePlan.Transit(
			firstLegPath, firstLegDuration,
			routeId,
			boarding.getId().getId(),
			alighting.getId().getId(),
			lastLegPath, lastLegDuration
		);
	}

	private void recordFailure(String reason, AtomicLong counter,
								java.util.function.Supplier<String> message) {
		long count = counter.incrementAndGet();
		if (count <= FAIL_LOG_LIMIT_PER_REASON) {
			log.info("[transit/plan/{} {}] {}", reason, count, message.get());
		}
	}

	/** Top-K brute-force nearest-stop scan, capped at {@code maxMeters}
	 * and at most {@code k} entries. */
	private static List<Stop> nearestStops(
		GtfsRelationalDaoImpl feed, double lat, double lon, double maxMeters, int k
	) {
		List<StopDistance> ranked = new ArrayList<>();
		for (Stop stop : feed.getAllStops()) {
			double sLat = stop.getLat();
			double sLon = stop.getLon();
			if (sLat == 0.0 && sLon == 0.0) {
				continue;
			}
			double distance = GameState.haversineMetres(lat, lon, sLat, sLon);
			if (distance > maxMeters) {
				continue;
			}
			ranked.add(new StopDistance(stop, distance));
		}
		ranked.sort((a, b) -> Double.compare(a.distance, b.distance));
		List<Stop> result = new ArrayList<>(Math.min(k, ranked.size()));
		for (int i = 0; i < Math.min(k, ranked.size()); i++) {
			result.add(ranked.get(i).stop);
		}
		return result;
	}

	/** Find the first trip visiting {@code boarding} that ALSO visits
	 * {@code alighting} later in its stop_sequence. Uses the pre-built
	 * {@code stopTripsIndex} so we only inspect trips that actually
	 * touch the boarding stop. */
	private static @Nullable TripMatch findConnectingTrip(
		GtfsRelationalDaoImpl feed,
		Map<String, List<Trip>> stopTripsIndex,
		Stop boarding, Stop alighting
	) {
		List<Trip> candidates = stopTripsIndex.get(boarding.getId().getId());
		if (candidates == null) {
			return null;
		}
		for (Trip trip : candidates) {
			List<StopTime> stopTimes = feed.getStopTimesForTrip(trip);
			int boardingIdx = -1;
			int alightingIdx = -1;
			for (int i = 0; i < stopTimes.size(); i++) {
				StopTime stopTime = stopTimes.get(i);
				if (stopTime.getStop() == null) {
					continue;
				}
				if (boardingIdx < 0 && stopTime.getStop().getId().equals(boarding.getId())) {
					boardingIdx = i;
				} else if (boardingIdx >= 0
					&& stopTime.getStop().getId().equals(alighting.getId())) {
					alightingIdx = i;
					break;
				}
			}
			if (boardingIdx < 0 || alightingIdx < 0) {
				continue;
			}
			Route route = trip.getRoute();
			String routeId = route != null && route.getId() != null
				? route.getId().getId() : null;
			return new TripMatch(trip, stopTimes, boardingIdx, alightingIdx,
				trip.getId().getId(), routeId);
		}
		return null;
	}

	/** Phase-21: build the bus-leg polyline from the trip's GTFS shape
	 * (or its ordered intermediate stop sequence as a fallback). Used by
	 * {@link SnapshotTransitSchedule} to render the cargo bus along the
	 * same line as the ambient bus on this route. Public so the adapter
	 * can call it without re-implementing nearest-shape-index. */
	public static List<LatLon> buildBusPolyline(
		@Nullable GtfsRelationalDaoImpl feed,
		Trip trip,
		List<StopTime> stopTimes,
		int boardingIdx,
		int alightingIdx,
		Stop boarding,
		Stop alighting
	) {
		if (feed != null && trip.getShapeId() != null) {
			List<ShapePoint> raw = feed.getShapePointsForShapeId(trip.getShapeId());
			if (raw != null && raw.size() >= 2) {
				List<ShapePoint> sorted = new ArrayList<>(raw);
				sorted.sort(Comparator.comparingInt(ShapePoint::getSequence));
				int boardShapeIdx = nearestShapeIndex(sorted, boarding.getLat(), boarding.getLon());
				int alightShapeIdx = nearestShapeIndex(sorted, alighting.getLat(), alighting.getLon());
				if (boardShapeIdx < alightShapeIdx) {
					List<LatLon> out = new ArrayList<>(alightShapeIdx - boardShapeIdx + 1);
					out.add(new LatLon(boarding.getLat(), boarding.getLon()));
					for (int i = boardShapeIdx + 1; i < alightShapeIdx; i++) {
						ShapePoint p = sorted.get(i);
						out.add(new LatLon(p.getLat(), p.getLon()));
					}
					out.add(new LatLon(alighting.getLat(), alighting.getLon()));
					if (out.size() >= 2) {
						return out;
					}
				}
			}
		}
		// Fallback: ordered intermediate stop coordinates.
		List<LatLon> out = new ArrayList<>(alightingIdx - boardingIdx + 1);
		for (int i = boardingIdx; i <= alightingIdx; i++) {
			org.onebusaway.gtfs.model.StopLocation s = stopTimes.get(i).getStop();
			if (!(s instanceof Stop concrete)) {
				continue;
			}
			out.add(new LatLon(concrete.getLat(), concrete.getLon()));
		}
		if (out.size() < 2) {
			return List.of(
				new LatLon(boarding.getLat(), boarding.getLon()),
				new LatLon(alighting.getLat(), alighting.getLon()));
		}
		return out;
	}

	private static int nearestShapeIndex(List<ShapePoint> sorted, double lat, double lon) {
		int best = 0;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int i = 0; i < sorted.size(); i++) {
			ShapePoint p = sorted.get(i);
			double d = GameState.haversineMetres(lat, lon, p.getLat(), p.getLon());
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}

	private record TripMatch(
		Trip trip,
		List<StopTime> stopTimes,
		int boardingIdx,
		int alightingIdx,
		String tripId,
		@Nullable String routeId
	) {
	}

	private record StopDistance(Stop stop, double distance) {
	}
}

