package com.dimsumdetours.config;

import com.dimsumdetours.sim.content.ContentRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes framework-agnostic simulation singletons as Spring beans.
 *
 * <p>Keeping these wirings here lets the {@code com.dimsumdetours.sim} package stay free of
 * Spring imports — important for a future Unity port.
 */
@Configuration
public class SimulationConfiguration {

	@Bean
	public ContentRegistry contentRegistry() {
		return new ContentRegistry();
	}
}
