package com.dimsumdetours.api;

import com.dimsumdetours.gtfs.BoundingBox;
import com.dimsumdetours.gtfs.GtfsLoader;
import lombok.RequiredArgsConstructor;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.Stop;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * Reactive endpoints for inspecting GTFS feeds available in the configured directory.
 *
 * <p>Parsing a feed is blocking; we offload it onto {@code Schedulers.boundedElastic()}.
 */
@RestController
@RequestMapping(path = "/api/gtfs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GtfsController {

	private final GtfsLoader loader;


	@GetMapping("/feeds")
	public Mono<List<String>> listFeeds() {
		return Mono.fromCallable(() -> List.copyOf(loader.listFeeds()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/feeds/{name}/summary")
	public Mono<FeedSummary> summary(@PathVariable String name) {
		return Mono.fromCallable(() -> {
				GtfsRelationalDaoImpl gtfsData = loader.loadFeed(name);
				return new FeedSummary(
					name,
					gtfsData.getAllAgencies().stream().map(Agency::getName).toList(),
					gtfsData.getAllStops().size(),
					gtfsData.getAllRoutes().size(),
					gtfsData.getAllTrips().size());
			})
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/feeds/{name}/bbox")
	public Mono<BoundingBox> boundingBox(@PathVariable String name) {
		return Mono.fromCallable(() -> loader.computeBoundingBox(name))
			.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * Phase-7: compute a transit-aware polyline between two arbitrary points using the loaded
	 * GTFS feed's stops as anchors. The current heuristic returns four waypoints —
	 * {@code [from, nearest-stop-to-from, nearest-stop-to-to, to]} — so the delivery animation
	 * visibly bends through the transit network instead of cutting across the city in a straight
	 * line. A proper trip-shape walk lands when the transit-graph router does in Phase 8; this
	 * is the cheap-but-honest stop-gap that satisfies the "uses GTFS data" promise of the
	 * Phase-7 task list. If the two endpoints share a single nearest stop, the duplicate is
	 * elided so the animation isn't a degenerate jiggle.
	 */
	@GetMapping("/feeds/{name}/route")
	public Mono<RouteResponse> route(
		@PathVariable String name,
		@RequestParam double fromLat,
		@RequestParam double fromLon,
		@RequestParam double toLat,
		@RequestParam double toLon
	) {
		return Mono.fromCallable(() -> {
				GtfsRelationalDaoImpl gtfsData = loader.loadFeed(name);
				Stop fromStop = nearestStop(gtfsData, fromLat, fromLon);
				Stop toStop = nearestStop(gtfsData, toLat, toLon);
				List<double[]> waypoints = new ArrayList<>(4);
				waypoints.add(new double[]{fromLat, fromLon});
				if (fromStop != null) {
					waypoints.add(new double[]{fromStop.getLat(), fromStop.getLon()});
				}
				if (toStop != null && toStop != fromStop) {
					waypoints.add(new double[]{toStop.getLat(), toStop.getLon()});
				}
				waypoints.add(new double[]{toLat, toLon});
				return new RouteResponse(waypoints);
			})
			.subscribeOn(Schedulers.boundedElastic());
	}

	private static @org.jspecify.annotations.Nullable Stop nearestStop(
		GtfsRelationalDaoImpl gtfsData, double lat, double lon
	) {
		Stop best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Stop stop : gtfsData.getAllStops()) {
			double stopLat = stop.getLat();
			double stopLon = stop.getLon();
			if (stopLat == 0.0 && stopLon == 0.0) {
				continue;
			}
			double dLat = stopLat - lat;
			double dLon = stopLon - lon;
			// Equirectangular approximation is plenty for "which stop is closest" within a
			// single GTFS bounding box; no need to swing a haversine here.
			double distanceSquared = dLat * dLat + dLon * dLon;
			if (distanceSquared < bestDistance) {
				bestDistance = distanceSquared;
				best = stop;
			}
		}
		return best;
	}

	public record RouteResponse(List<double[]> waypoints) {
	}

	public record FeedSummary(
		String name,
		List<String> agencies,
		int stops,
		int routes,
		int trips
	) {
	}
}
