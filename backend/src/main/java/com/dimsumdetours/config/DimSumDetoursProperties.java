package com.dimsumdetours.config;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * External (per-deployment) configuration. Bound from {@code dimsumdetours.*} in application.yml.
 *
 * <p>Distinct from {@link GameConstants} which holds engine/balance numbers.
 *
 * <p>All fields are nullable until Spring populates them from configuration; downstream
 * components should validate at startup if a value is required.
 */
@Configuration
@ConfigurationProperties(prefix = "dimsumdetours")
@Getter
@Setter
public class DimSumDetoursProperties {

	private @Nullable String gtfsDir;
	private @Nullable String contentDir;
	private @Nullable String modsDir;
	private @Nullable String overpassUrl;
	private @Nullable String osmCacheDir;

	/**
	 * Phase-14: world bounding box used by the OSM street-graph loader. Matches the
	 * placement-zones bbox the frontend asks for at boot. Default is Hong Kong —
	 * dim-sum's home turf, the project's namesake. Configure as
	 * {@code dimsumdetours.world-bbox.south/west/north/east} in {@code application.yml}.
	 */
	private WorldBbox worldBbox = new WorldBbox();

	@Getter
	@Setter
	public static class WorldBbox {
		/** Hong Kong: roughly Lantau in the west to Sai Kung in the east, the
		 * Northern Frontier Closed Area down to the Stanley peninsula. */
		private double south = 22.150;
		private double west = 113.830;
		private double north = 22.580;
		private double east = 114.450;
	}
}
