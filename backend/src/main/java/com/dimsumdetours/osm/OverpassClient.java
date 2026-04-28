package com.dimsumdetours.osm;

import com.dimsumdetours.config.DimSumDetoursProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Thin client over the public Overpass API.
 *
 * <p>Phase 2: bounding-box queries for the four placement-zone layers we care about:
 * <ul>
 *   <li><strong>Parks</strong> ({@code leisure=park}) — community-garden farms.</li>
 *   <li><strong>Farmland</strong> ({@code landuse=farmland}) — full-size farms.</li>
 *   <li><strong>Water</strong> ({@code natural=water}, {@code natural=coastline}) —
 *       fishing ports, salt collectors.</li>
 *   <li><strong>Commercial</strong> ({@code landuse=commercial}) — factories.</li>
 * </ul>
 *
 * <p>Returns raw GeoJSON-shaped JSON strings; the frontend renders them on Leaflet directly.
 * Responses should be cached to disk by callers — see Phase 2 roadmap.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverpassClient {

	private static final String OVERPASS_QL_TEMPLATE = """
		[out:json][timeout:60];
		(
		  way["leisure"="park"](%1$s);
		  way["landuse"="farmland"](%1$s);
		  way["natural"="water"](%1$s);
		  way["natural"="coastline"](%1$s);
		  way["landuse"="commercial"](%1$s);
		  relation["leisure"="park"](%1$s);
		  relation["landuse"="farmland"](%1$s);
		  relation["natural"="water"](%1$s);
		  relation["landuse"="commercial"](%1$s);
		);
		out geom;
		""";

	private final DimSumDetoursProperties properties;
	private final WebClient webClient = WebClient.builder().build();

	/**
	 * Fetch all placement-zone polygons inside the bounding box.
	 *
	 * @param south south latitude
	 * @param west  west longitude
	 * @param north north latitude
	 * @param east  east longitude
	 * @return raw Overpass JSON response (caller is responsible for parsing/caching)
	 */
	public Mono<String> fetchPlacementZones(double south, double west, double north, double east) {
		String endpoint = properties.getOverpassUrl();
		if (StringUtils.isBlank(endpoint)) {
			return Mono.error(new IllegalStateException("dimsumdetours.overpass-url is not configured"));
		}
		String bbox = "%f,%f,%f,%f".formatted(south, west, north, east);
		String query = OVERPASS_QL_TEMPLATE.formatted(bbox);

		log.debug("Overpass query for bbox {}", bbox);
		return webClient.post()
			.uri(endpoint)
			.bodyValue("data=" + query)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.retrieve()
			.bodyToMono(String.class);
	}
}
