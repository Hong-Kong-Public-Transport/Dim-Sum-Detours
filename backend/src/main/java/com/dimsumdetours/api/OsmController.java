package com.dimsumdetours.api;

import com.dimsumdetours.gtfs.BoundingBox;
import com.dimsumdetours.gtfs.GtfsLoader;
import com.dimsumdetours.osm.OverpassClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Phase 2: serve OSM placement-zone polygons for a given bounding box (or for a named GTFS feed).
 *
 * <p>Frontend calls this once it knows the GTFS bounding box and renders the response
 * directly as Leaflet overlays.
 */
@RestController
@RequestMapping(path = "/api/osm", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OsmController {

	private final OverpassClient overpassClient;
	private final GtfsLoader gtfsLoader;

	@GetMapping("/placement-zones")
	public Mono<String> placementZones(
		@RequestParam double south,
		@RequestParam double west,
		@RequestParam double north,
		@RequestParam double east) {
		return overpassClient.fetchPlacementZones(south, west, north, east);
	}

	/**
	 * Convenience: fetch placement zones using the bounding box of a named GTFS feed.
	 * Saves the frontend a round-trip.
	 */
	@GetMapping("/placement-zones/by-feed/{name}")
	public Mono<String> placementZonesByFeed(@PathVariable String name) {
		return Mono.fromCallable(() -> gtfsLoader.computeBoundingBox(name))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(bbox -> overpassClient.fetchPlacementZones(
				bbox.south(), bbox.west(), bbox.north(), bbox.east()));
	}
}
