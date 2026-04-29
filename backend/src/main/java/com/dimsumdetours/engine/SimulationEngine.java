package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.state.GameState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Phase 4 simulation engine. Owns the real-time → game-time conversion and ticks the
 * {@link GameState} clock at {@link GameConstants#SIM_TICK_MILLIS} intervals.
 *
 * <p>Lives <strong>outside</strong> {@code sim/} so the simulation core stays Spring-free
 * (per the README rule). The engine itself is a thin Spring wrapper over
 * {@link GameState#advanceClock(long)} and a multicast {@link Sinks.Many} that broadcasts
 * the latest snapshot to any SSE subscribers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationEngine {

	private final GameState gameState;

	private final Sinks.Many<GameState.ClockSnapshot> clockSink =
		Sinks.many().multicast().onBackpressureBuffer(1, false);

	private @org.jspecify.annotations.Nullable Disposable tickSubscription;

	/** Carry-over fractional minutes between ticks so the clock stays integer. */
	private double minuteAccumulator = 0.0;

	@PostConstruct
	void start() {
		log.info("Starting simulation engine: tick {} ms, 1× = {} game-min/real-sec",
			GameConstants.SIM_TICK_MILLIS, GameConstants.GAME_MINUTES_PER_REAL_SECOND_AT_1X);
		tickSubscription = Flux.interval(Duration.ofMillis(GameConstants.SIM_TICK_MILLIS))
			.subscribeOn(Schedulers.parallel())
			.subscribe(unused -> tick());
	}

	@PreDestroy
	void stop() {
		if (tickSubscription != null) {
			tickSubscription.dispose();
		}
	}

	private void tick() {
		try {
			GameState.ClockSnapshot before = gameState.getClockSnapshot();
			if (before.playing() && before.speed() > 0) {
				double tickSeconds = GameConstants.SIM_TICK_MILLIS / 1000.0;
				double deltaMinutes = before.speed()
					* GameConstants.GAME_MINUTES_PER_REAL_SECOND_AT_1X
					* tickSeconds;
				minuteAccumulator += deltaMinutes;
				long whole = (long) minuteAccumulator;
				if (whole > 0) {
					minuteAccumulator -= whole;
					gameState.advanceClock(whole);
				}
			}
			clockSink.tryEmitNext(gameState.getClockSnapshot());
		} catch (RuntimeException ex) {
			log.warn("Simulation tick failed: {}", ex.getMessage());
		}
	}

	/** Live stream of clock snapshots, one per tick. Subscribed to by the SSE controller. */
	public Flux<GameState.ClockSnapshot> clockStream() {
		return clockSink.asFlux();
	}
}

