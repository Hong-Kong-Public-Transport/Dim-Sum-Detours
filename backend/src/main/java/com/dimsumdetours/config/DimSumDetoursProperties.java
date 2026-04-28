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
}
