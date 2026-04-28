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
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collection;

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
	 * Parse a feed by file name (must already exist in the GTFS directory).
	 */
	public GtfsRelationalDaoImpl loadFeed(String fileName) throws Exception {
		if (StringUtils.isBlank(fileName) || !FilenameUtils.isExtension(fileName, "zip")) {
			throw new IllegalArgumentException("Invalid GTFS feed name: " + fileName);
		}
		File file = new File(resolveGtfsDir(), fileName);
		if (!file.isFile()) {
			throw new IllegalArgumentException("GTFS feed not found: " + fileName);
		}
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

	private File resolveGtfsDir() {
		String configured = properties.getGtfsDir();
		if (StringUtils.isBlank(configured)) {
			throw new IllegalStateException("dimsumdetours.gtfs-dir is not configured");
		}
		return new File(configured).getAbsoluteFile();
	}
}
