package com.dimsumdetours.api;

import com.dimsumdetours.osm.OverpassClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Phase 2: serve OSM placement-zone polygons for a given bounding box.
 *
 * <p>Frontend calls this once it knows the GTFS bounding box and renders the response
 * directly as Leaflet overlays.
 */
@RestController
@RequestMapping(path = "/api/osm", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OsmController {

	private final OverpassClient overpassClient;

	@GetMapping("/placement-zones")
	public Mono<String> placementZones(
		@RequestParam double south,
		@RequestParam double west,
		@RequestParam double north,
		@RequestParam double east) {
		return overpassClient.fetchPlacementZones(south, west, north, east);
	}
}
