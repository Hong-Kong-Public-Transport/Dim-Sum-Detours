package com.dimsumdetours.config;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.state.GameState;
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

	/**
	 * Phase 3: a single in-memory game state. TODO(phase-6): swap for a JPA-backed repository
	 * once the "is it fun?" checkpoint passes and we want player saves to survive restarts.
	 */
	@Bean
	public GameState gameState(ContentRegistry contentRegistry) {
		return new GameState(contentRegistry);
	}
}
