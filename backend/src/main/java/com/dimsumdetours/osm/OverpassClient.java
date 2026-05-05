package com.dimsumdetours.osm;

import com.dimsumdetours.config.DimSumDetoursProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
 * <p>Returns raw Overpass JSON strings (the frontend converts them to Leaflet layers).
 * Responses are cached to disk per bounding box so we don't hammer the public API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverpassClient {

	/** Bump when {@link #OVERPASS_QL_TEMPLATE} changes so old caches are ignored. */
	private static final String CACHE_VERSION = "v2";

	private static final String OVERPASS_QL_TEMPLATE = """
		[out:json][timeout:60];
		(
		  way["leisure"="park"](%1$s);
		  way["landuse"="farmland"](%1$s);
		  way["natural"="water"](%1$s);
		  way["natural"="coastline"](%1$s);
		  way["landuse"="commercial"](%1$s);
		  way["landuse"="residential"](%1$s);
		  relation["leisure"="park"](%1$s);
		  relation["landuse"="farmland"](%1$s);
		  relation["natural"="water"](%1$s);
		  relation["landuse"="commercial"](%1$s);
		  relation["landuse"="residential"](%1$s);
		);
		out geom;
		""";

	private final DimSumDetoursProperties properties;
	private final WebClient webClient = WebClient.builder()
		// Overpass responses can be very large for a city-sized bbox; lift the
		// 256 KiB default to 1 GiB so we never truncate. Real heap cost = response.
		.codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024 * 1024))
		.build();

	/**
	 * Fetch all placement-zone polygons inside the bounding box.
	 *
	 * @param south south latitude
	 * @param west  west longitude
	 * @param north north latitude
	 * @param east  east longitude
	 * @return raw Overpass JSON response
	 */
	public Mono<String> fetchPlacementZones(double south, double west, double north, double east) {
		String endpoint = properties.getOverpassUrl();
		if (StringUtils.isBlank(endpoint)) {
			return Mono.error(new IllegalStateException("dimsumdetours.overpass-url is not configured"));
		}
		String bbox = "%f,%f,%f,%f".formatted(south, west, north, east);
		String query = OVERPASS_QL_TEMPLATE.formatted(bbox);
		String cacheDir = properties.getOsmCacheDir();
		String cacheKey = cacheKey(south, west, north, east);

		return readCache(cacheDir, cacheKey)
			.switchIfEmpty(Mono.defer(() -> {
				log.debug("Overpass query for bbox {} (cache miss)", bbox);
				return webClient.post()
					.uri(endpoint)
					.bodyValue("data=" + query)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.retrieve()
					.bodyToMono(String.class)
					.flatMap(body -> writeCache(cacheDir, cacheKey, body).thenReturn(body));
			}));
	}

	private static String cacheKey(double south, double west, double north, double east) {
		// Round to 4dp (~11 m) so trivially-different bboxes hit the same cache entry.
		return "placement-zones_%s_%.4f_%.4f_%.4f_%.4f.json"
			.formatted(CACHE_VERSION, south, west, north, east);
	}

	private static Mono<String> readCache(@org.jspecify.annotations.Nullable String cacheDir, String key) {
		if (StringUtils.isBlank(cacheDir)) {
			return Mono.empty();
		}
		return Mono.fromCallable(() -> {
				Path file = Path.of(cacheDir, key);
				if (!Files.isRegularFile(file)) {
					return null;
				}
				log.debug("Overpass cache hit: {}", file);
				return Files.readString(file, StandardCharsets.UTF_8);
			})
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(Mono::justOrEmpty);
	}

	private static Mono<Void> writeCache(@org.jspecify.annotations.Nullable String cacheDir, String key, String body) {
		if (StringUtils.isBlank(cacheDir)) {
			return Mono.empty();
		}
		return Mono.fromRunnable(() -> {
				try {
					File dir = new File(cacheDir);
					FileUtils.forceMkdir(dir);
					Path target = dir.toPath().resolve(key);
					Path tmp = dir.toPath().resolve(key + ".tmp");
					Files.writeString(tmp, body, StandardCharsets.UTF_8);
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					log.debug("Cached Overpass response to {}", target);
				} catch (IOException e) {
					log.warn("Failed to write Overpass cache for {}: {}", key, e.getMessage());
				}
			})
			.subscribeOn(Schedulers.boundedElastic())
			.then();
	}
}
