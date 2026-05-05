package com.dimsumdetours.gtfs;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.vehicle.VehicleHandoff;
import com.dimsumdetours.sim.model.vehicle.VehicleKind;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.MultiLegPlanner;
import com.dimsumdetours.sim.state.VehicleChain;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Phase-16 GTFS-backed {@link MultiLegPlanner}. For a long-haul source →
 * destination pair, finds:
 * <ol>
 *   <li>the nearest GTFS stop to the source within
 *       {@link GameConstants#MAX_TRANSIT_STOP_WALK_METERS},</li>
 *   <li>the nearest GTFS stop to the destination within the same range,</li>
 *   <li>a trip in the loaded feed that visits the boarding stop before the
 *       alighting stop (in {@code stop_sequence} order).</li>
 * </ol>
 * Composes a {@code Robot(source→boarding) → Bus(boarding→alighting) →
 * Robot(alighting→destination)} chain. Bus leg duration is taken from
 * {@code stop_times.txt} when both stops have departure/arrival times in the
 * trip's stop_times; otherwise we fall back to a {@link
 * GameConstants#BUS_METERS_PER_GAME_MINUTE} estimate.
 *
 * <p>If no GTFS feed exists in {@code dimsumdetours.gtfs-dir} the planner stays
 * disabled and returns {@link Optional#empty()} from every call — equivalent
 * to {@link MultiLegPlanner#disabled()}.
 *
 * <p>Picking the active feed: first feed in
 * {@link GtfsLoader#listFeeds()} (alphabetically) is loaded eagerly at
 * {@link PostConstruct} time. Swapping feeds at runtime is out of scope for
 * Phase-16 — a backend restart picks up a new file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GtfsMultiLegPlanner implements MultiLegPlanner {

	private final GtfsLoader loader;

	private volatile @Nullable GtfsRelationalDaoImpl activeFeed;
	private volatile @Nullable String activeFeedName;

	@PostConstruct
	void initialise() {
		List<String> feeds = loader.listFeeds();
		if (feeds.isEmpty()) {
			log.info("No GTFS feeds found; multi-leg planner stays disabled");
			return;
		}
		String firstFeed = feeds.getFirst();
		try {
			activeFeed = loader.loadFeed(firstFeed);
			activeFeedName = firstFeed;
			log.info("GtfsMultiLegPlanner active on feed '{}': {} stops, {} trips",
				firstFeed,
				activeFeed.getAllStops().size(),
				activeFeed.getAllTrips().size());
		} catch (Exception ex) {
			log.warn("Failed to load GTFS feed '{}'; multi-leg planner stays disabled: {}",
				firstFeed, ex.getMessage());
		}
	}

	@Override
	public Optional<VehicleChain> plan(LatLon source, LatLon destination, long departureGameMinutes) {
		GtfsRelationalDaoImpl feed = activeFeed;
		if (feed == null) {
			return Optional.empty();
		}
		Stop boarding = nearestStop(feed, source.lat(), source.lon(),
			GameConstants.MAX_TRANSIT_STOP_WALK_METERS);
		Stop alighting = nearestStop(feed, destination.lat(), destination.lon(),
			GameConstants.MAX_TRANSIT_STOP_WALK_METERS);
		if (boarding == null || alighting == null || boarding.getId().equals(alighting.getId())) {
			return Optional.empty();
		}

		// Find any trip visiting boarding-then-alighting in stop_sequence order.
		TripMatch match = findConnectingTrip(feed, boarding, alighting);
		if (match == null) {
			return Optional.empty();
		}

		LatLon boardingLatLon = new LatLon(boarding.getLat(), boarding.getLon());
		LatLon alightingLatLon = new LatLon(alighting.getLat(), alighting.getLon());

		long firstLegMeters = (long) Math.ceil(GameState.haversineMetres(
			source.lat(), source.lon(), boarding.getLat(), boarding.getLon()));
		long firstLegDuration = Math.max(1L, Math.round(
			firstLegMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));

		long busMeters = (long) Math.ceil(GameState.haversineMetres(
			boarding.getLat(), boarding.getLon(),
			alighting.getLat(), alighting.getLon()));
		long busDuration = match.scheduledDurationGameMinutes > 0
			? match.scheduledDurationGameMinutes
			: Math.max(1L, Math.round(busMeters / GameConstants.BUS_METERS_PER_GAME_MINUTE));

		long lastLegMeters = (long) Math.ceil(GameState.haversineMetres(
			alighting.getLat(), alighting.getLon(),
			destination.lat(), destination.lon()));
		long lastLegDuration = Math.max(1L, Math.round(
			lastLegMeters / GameConstants.ROBOT_METERS_PER_GAME_MINUTE));

		// Build the chain inside-out: terminal robot → bus → first robot's handoff.
		VehicleHandoff finalLegHandoff = new VehicleHandoff(
			VehicleKind.ROBOT,
			List.of(alightingLatLon, destination),
			lastLegDuration,
			null,
			null,
			null
		);
		VehicleHandoff busHandoff = new VehicleHandoff(
			VehicleKind.BUS,
			List.of(boardingLatLon, alightingLatLon),
			busDuration,
			match.tripId,
			match.routeId,
			finalLegHandoff
		);
		return Optional.of(new VehicleChain(
			List.of(source, boardingLatLon),
			firstLegDuration,
			busHandoff
		));
	}

	/** Brute-force nearest-stop scan, capped at {@code maxMeters}. */
	private static @Nullable Stop nearestStop(
		GtfsRelationalDaoImpl feed, double lat, double lon, double maxMeters
	) {
		Stop best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
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
			if (distance < bestDistance) {
				best = stop;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Find the first trip in the feed whose stop_times visit {@code boarding}
	 * before {@code alighting}. Returns the trip's id + route id + scheduled
	 * minute delta between the two stops, or {@code null} if no such trip exists.
	 */
	private static @Nullable TripMatch findConnectingTrip(
		GtfsRelationalDaoImpl feed, Stop boarding, Stop alighting
	) {
		for (Trip trip : feed.getAllTrips()) {
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
			long durationMinutes = scheduledDurationMinutes(
				stopTimes.get(boardingIdx), stopTimes.get(alightingIdx));
			Route route = trip.getRoute();
			String routeId = route != null && route.getId() != null
				? route.getId().getId() : null;
			return new TripMatch(trip.getId().getId(), routeId, durationMinutes);
		}
		return null;
	}

	/**
	 * GTFS times are seconds-since-midnight. We want game-minutes between two
	 * stops; -1 sentinel means missing on either side and the caller should
	 * fall back to the BUS_METERS_PER_GAME_MINUTE estimate.
	 */
	private static long scheduledDurationMinutes(StopTime boarding, StopTime alighting) {
		int depart = boarding.isDepartureTimeSet() ? boarding.getDepartureTime()
			: (boarding.isArrivalTimeSet() ? boarding.getArrivalTime() : -1);
		int arrive = alighting.isArrivalTimeSet() ? alighting.getArrivalTime()
			: (alighting.isDepartureTimeSet() ? alighting.getDepartureTime() : -1);
		if (depart < 0 || arrive < 0 || arrive <= depart) {
			return -1L;
		}
		return Math.max(1L, (arrive - depart) / 60L);
	}

	private record TripMatch(String tripId, @Nullable String routeId, long scheduledDurationGameMinutes) {
	}
}

