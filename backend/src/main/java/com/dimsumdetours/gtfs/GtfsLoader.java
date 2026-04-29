package com.dimsumdetours.gtfs;

import com.dimsumdetours.config.DimSumDetoursProperties;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * Loads GTFS feeds from {@code dimsumdetours.gtfs-dir}. Phase 1: lists feeds and parses on demand.
 *
 * <p>Heavy work (reading a multi-MB feed) is synchronous-blocking; callers in the reactive
 * web layer should wrap calls in {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GtfsLoader {

	private final DimSumDetoursProperties properties;

	/**
	 * Single-flight cache: one {@link CompletableFuture} per feed name. The first caller starts
	 * the parse; concurrent callers (e.g. several browser tabs hitting the bbox endpoint while a
	 * 30 MB feed is still loading) await the same future instead of kicking off a second
	 * {@link GtfsReader}. Failed parses are evicted so the next caller can retry.
	 */
	private final ConcurrentMap<String, CompletableFuture<GtfsRelationalDaoImpl>> feedCache = new ConcurrentHashMap<>();

	/**
	 * List GTFS .zip files available in the configured GTFS directory.
	 */
	public ObjectList<String> listFeeds() {
		File dir = resolveGtfsDir();
		if (!dir.isDirectory()) {
			log.warn("GTFS directory does not exist: {}", dir.getAbsolutePath());
			return ObjectLists.emptyList();
		}
		Collection<File> zips = FileUtils.listFiles(dir, new String[]{"zip"}, false);
		ObjectList<String> names = new ObjectArrayList<>(zips.size());
		for (File f : zips) {
			names.add(f.getName());
		}
		return names;
	}

	/**
	 * Parse a feed by file name (must already exist in the GTFS directory). Cached after the
	 * first successful call; concurrent callers share one in-flight parse.
	 */
	public GtfsRelationalDaoImpl loadFeed(String fileName) throws Exception {
		if (StringUtils.isBlank(fileName) || !FilenameUtils.isExtension(fileName, "zip")) {
			throw new IllegalArgumentException("Invalid GTFS feed name: " + fileName);
		}
		CompletableFuture<GtfsRelationalDaoImpl> future = feedCache.computeIfAbsent(fileName, name -> {
			CompletableFuture<GtfsRelationalDaoImpl> placeholder = new CompletableFuture<>();
			// computeIfAbsent must be quick; complete the placeholder asynchronously so we don't
			// hold the bucket lock for the entire (potentially multi-minute) parse.
			Thread.ofVirtual().name("gtfs-load-" + name).start(() -> {
				try {
					placeholder.complete(parseFeed(name));
				} catch (Throwable t) {
					placeholder.completeExceptionally(t);
				}
			});
			return placeholder;
		});

		try {
			return future.join();
		} catch (Exception ex) {
			// Evict the failed future so future calls can retry instead of inheriting the failure.
			feedCache.remove(fileName, future);
			Throwable cause = (ex instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null)
				? ce.getCause() : ex;
			if (cause instanceof Exception checked) {
				throw checked;
			}
			throw new ExecutionException(cause);
		}
	}

	private GtfsRelationalDaoImpl parseFeed(String fileName) throws Exception {
		File file = new File(resolveGtfsDir(), fileName);
		if (!file.isFile()) {
			throw new IllegalArgumentException("GTFS feed not found: " + fileName);
		}
		log.info("Parsing GTFS feed '{}'…", fileName);
		GtfsRelationalDaoImpl dao = new GtfsRelationalDaoImpl();
		GtfsReader reader = new GtfsReader();
		reader.setInputLocation(file);
		reader.setEntityStore(dao);
		reader.run();
		log.info("Loaded GTFS feed '{}': {} stops, {} routes, {} trips",
			fileName,
			dao.getAllStops().size(),
			dao.getAllRoutes().size(),
			dao.getAllTrips().size());
		return dao;
	}

	/**
	 * Compute the bounding box of a feed from its {@code stops.txt}. Stops at exactly
	 * {@code (0,0)} are skipped (a common placeholder for unset coordinates).
	 *
	 * @throws IllegalStateException if the feed has no stops with valid coordinates.
	 */
	public BoundingBox computeBoundingBox(String fileName) throws Exception {
		GtfsRelationalDaoImpl dao = loadFeed(fileName);
		double south = Double.POSITIVE_INFINITY;
		double west = Double.POSITIVE_INFINITY;
		double north = Double.NEGATIVE_INFINITY;
		double east = Double.NEGATIVE_INFINITY;
		boolean any = false;
		for (Stop stop : dao.getAllStops()) {
			double lat = stop.getLat();
			double lon = stop.getLon();
			if (lat == 0.0 && lon == 0.0) {
				continue;
			}
			south = Math.min(south, lat);
			west = Math.min(west, lon);
			north = Math.max(north, lat);
			east = Math.max(east, lon);
			any = true;
		}
		if (!any) {
			throw new IllegalStateException("Feed '" + fileName + "' has no stops with valid coordinates");
		}
		return new BoundingBox(south, west, north, east);
	}

	private File resolveGtfsDir() {
		String configured = properties.getGtfsDir();
		if (StringUtils.isBlank(configured)) {
			throw new IllegalStateException("dimsumdetours.gtfs-dir is not configured");
		}
		return new File(configured).getAbsoluteFile();
	}
}
