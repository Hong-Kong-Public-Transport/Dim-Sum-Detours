package com.dimsumdetours.osm.routing;

import com.dimsumdetours.config.DimSumDetoursProperties;
import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.state.GameState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
 * Phase-14: pulls {@code way["highway"]} ways for the configured world bbox from
 * the Overpass API, caches the JSON to disk (same pattern as {@code OverpassClient}),
 * and parses it into an immutable {@link OsmStreetGraph}.
 *
 * <p>Returns {@link OsmStreetGraph#EMPTY} on any error — Overpass timeout,
 * malformed JSON, missing config — so the caller falls back to straight-line paths
 * rather than crashing the simulation.
 *
 * <p>Why not reuse {@code OverpassClient}? That class hard-codes the placement-zone
 * QL template; we'd otherwise have to either parameterise it (leaks an internal
 * concern across two callers) or duplicate the WebClient + cache scaffolding.
 * The duplication is small and the two responsibilities are genuinely distinct.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OsmHighwayLoader {

	/** Drivable / walkable highways only. Skips {@code motorway} (no pedestrian/robot
	 * access) and aerodromes/raceways. {@code service} captures driveways and
	 * parking-lot lanes which players' robots can legitimately use. */
	private static final String OVERPASS_QL_TEMPLATE = """
		[out:json][timeout:90];
		(
		  way["highway"~"^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|cycleway|path|track)$"](%1$s);
		);
		out geom;
		""";

	private final DimSumDetoursProperties properties;
	private final ObjectMapper objectMapper;
	private final WebClient webClient = WebClient.builder()
		// Highway responses for a city-sized bbox can run into the hundreds of MB —
		// Hong Kong's full street graph clears 100 MB easily. Lift the cap to 1 GiB so
		// Overpass responses are never truncated; the actual heap cost is whatever
		// Overpass returns, not the cap.
		.codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024 * 1024))
		.build();

	/**
	 * Fetch + parse the highway graph for the configured world bbox. Returns
	 * {@link OsmStreetGraph#EMPTY} on any failure (so the caller falls back to
	 * straight-line). Blocks the calling thread on Overpass IO — call from a
	 * background thread via {@code Schedulers.boundedElastic()}.
	 */
	public OsmStreetGraph loadWorldGraph() {
		DimSumDetoursProperties.WorldBbox bbox = properties.getWorldBbox();
		if (bbox == null) {
			log.info("OSM router disabled: no world-bbox configured.");
			return OsmStreetGraph.EMPTY;
		}
		return loadGraphForBbox(bbox.getSouth(), bbox.getWest(), bbox.getNorth(), bbox.getEast());
	}

	/**
	 * Phase-17: explicit-bbox entry point so {@link OsmRouter} can align the street
	 * graph with the active GTFS feed's footprint instead of whatever's in
	 * {@code application.yml}. Without this, a player who drops a Hong Kong feed
	 * into {@code data/gtfs/} but leaves the static config pointing at Bellingham
	 * would have buildings (snapped to the GTFS-feed-derived placement zones) sitting
	 * 10 000 km from any street node and silently fall back to straight-line.
	 */
	public OsmStreetGraph loadGraphForBbox(double south, double west, double north, double east) {
		String endpoint = properties.getOverpassUrl();
		if (StringUtils.isBlank(endpoint)) {
			log.info("OSM router disabled: no overpass-url configured.");
			return OsmStreetGraph.EMPTY;
		}
		try {
			String json = fetchHighwayJson(south, west, north, east).block();
			if (StringUtils.isBlank(json)) {
				return OsmStreetGraph.EMPTY;
			}
			return parse(json);
		} catch (RuntimeException ex) {
			log.warn("OSM highway load failed; falling back to straight-line routing: {}",
				ex.getMessage());
			return OsmStreetGraph.EMPTY;
		}
	}

	private Mono<String> fetchHighwayJson(double south, double west, double north, double east) {
		String endpoint = properties.getOverpassUrl();
		if (StringUtils.isBlank(endpoint)) {
			return Mono.empty();
		}
		String bboxStr = "%f,%f,%f,%f".formatted(south, west, north, east);
		String query = OVERPASS_QL_TEMPLATE.formatted(bboxStr);
		String cacheDir = properties.getOsmCacheDir();
		String cacheKey = cacheKey(south, west, north, east);
		return readCache(cacheDir, cacheKey)
			.switchIfEmpty(Mono.defer(() -> {
				log.info("OSM highways: fetching from Overpass for bbox {} (cache miss)", bboxStr);
				return webClient.post()
					.uri(endpoint)
					.bodyValue("data=" + query)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.retrieve()
					.bodyToMono(String.class)
					.flatMap(body -> writeCache(cacheDir, cacheKey, body).thenReturn(body));
			}));
	}

	/**
	 * Parse Overpass {@code out geom} response into an {@link OsmStreetGraph}. Each
	 * way carries a list of geometry points; we treat consecutive points as a chain
	 * of bidirectional edges (no one-way handling — robots / pedestrians can traverse
	 * either direction on every supported highway tag). Node identity is derived from
	 * the {@code (lat, lon)} pair rounded to 7 decimals so adjacent ways that share
	 * an endpoint actually meet in the graph (Overpass {@code geom} doesn't echo
	 * shared-node ids).
	 */
	OsmStreetGraph parse(String overpassJson) {
		try {
			JsonNode root = objectMapper.readTree(overpassJson);
			JsonNode elements = root.get("elements");
			if (elements == null || !elements.isArray()) {
				log.warn("OSM highway JSON has no 'elements' array; falling back to straight-line.");
				return OsmStreetGraph.EMPTY;
			}
			DoubleArrayList nodeLats = new DoubleArrayList();
			DoubleArrayList nodeLons = new DoubleArrayList();
			Long2IntOpenHashMap nodeIndexByCoordKey = OsmStreetGraph.newOsmIdIndex(elements.size() * 4);
			IntArrayList edgeFrom = new IntArrayList();
			IntArrayList edgeTo = new IntArrayList();
			DoubleArrayList edgeLengths = new DoubleArrayList();

			for (JsonNode element : elements) {
				JsonNode geometry = element.get("geometry");
				if (geometry == null || !geometry.isArray() || geometry.size() < 2) {
					continue;
				}
				int previousNodeIdx = -1;
				double previousLat = 0.0;
				double previousLon = 0.0;
				for (JsonNode point : geometry) {
					double lat = point.path("lat").asDouble();
					double lon = point.path("lon").asDouble();
					int currentIdx = internNode(nodeIndexByCoordKey, nodeLats, nodeLons, lat, lon);
					if (previousNodeIdx >= 0 && previousNodeIdx != currentIdx) {
						double lengthMeters = GameState.haversineMetres(
							previousLat, previousLon, lat, lon);
						// Bidirectional — see class javadoc.
						edgeFrom.add(previousNodeIdx);
						edgeTo.add(currentIdx);
						edgeLengths.add(lengthMeters);
						edgeFrom.add(currentIdx);
						edgeTo.add(previousNodeIdx);
						edgeLengths.add(lengthMeters);
					}
					previousNodeIdx = currentIdx;
					previousLat = lat;
					previousLon = lon;
				}
			}
			OsmStreetGraph graph = OsmStreetGraph.build(nodeLats, nodeLons, edgeFrom, edgeTo, edgeLengths);
			log.info("OSM street graph built: {} nodes, {} directed edges",
				graph.nodeCount(), graph.edgeCount());
			return graph;
		} catch (IOException | RuntimeException ex) {
			log.warn("OSM highway JSON parse failed; falling back to straight-line: {}",
				ex.getMessage());
			return OsmStreetGraph.EMPTY;
		}
	}

	/** Reuse a node index when an existing point is within ~1 cm; otherwise add a new one. */
	private static int internNode(
		Long2IntOpenHashMap byCoordKey,
		DoubleArrayList lats,
		DoubleArrayList lons,
		double lat, double lon
	) {
		long key = coordKey(lat, lon);
		int existing = byCoordKey.get(key);
		if (existing >= 0) {
			return existing;
		}
		int idx = lats.size();
		lats.add(lat);
		lons.add(lon);
		byCoordKey.put(key, idx);
		return idx;
	}

	/** Pack lat/lon into a single 64-bit coordinate key (≈1 cm resolution). */
	private static long coordKey(double lat, double lon) {
		// Round to 7 decimals = ~1 cm. Bias by 1e7 to make integers, then pack.
		long latPart = Math.round((lat + 90.0) * 1e7);  // 0 .. ~1.8e9
		long lonPart = Math.round((lon + 180.0) * 1e7); // 0 .. ~3.6e9 — needs 32 bits
		return (latPart << 32) | (lonPart & 0xFFFFFFFFL);
	}

	private static String cacheKey(double south, double west, double north, double east) {
		return "highways_%s_%.4f_%.4f_%.4f_%.4f.json".formatted(
			GameConstants.OSM_HIGHWAYS_CACHE_VERSION, south, west, north, east);
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
				log.debug("OSM highway cache hit: {}", file);
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
					log.debug("Cached OSM highway response to {}", target);
				} catch (IOException e) {
					log.warn("Failed to write OSM highway cache for {}: {}", key, e.getMessage());
				}
			})
			.subscribeOn(Schedulers.boundedElastic())
			.then();
	}
}


