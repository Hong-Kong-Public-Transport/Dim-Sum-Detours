package com.dimsumdetours.api;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.engine.SimulationEngine;
import com.dimsumdetours.sim.state.GameState;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * Phase 4: read the game clock and steer it (speed, pause/resume).
 *
 * <p>{@code GET /stream} is a Server-Sent-Events feed driven by the
 * {@link SimulationEngine}'s tick sink — one event per tick (~10 Hz at default config).
 */
@RestController
@RequestMapping(path = "/api/game/clock", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ClockController {

	private final GameState gameState;
	private final SimulationEngine engine;

	@GetMapping("")
	public Mono<GameState.ClockSnapshot> getClock() {
		return Mono.fromSupplier(gameState::getClockSnapshot);
	}

	@PostMapping("/speed")
	public Mono<ResponseEntity<GameState.ClockSnapshot>> setSpeed(@RequestBody SpeedRequest body) {
		boolean valid = Arrays.stream(GameConstants.GAME_SPEEDS).anyMatch(s -> s == body.speed());
		if (!valid) {
			return Mono.just(ResponseEntity.badRequest().build());
		}
		return Mono.fromSupplier(() -> {
			gameState.setClockSpeed(body.speed());
			// Setting speed to 0 is the same as pausing in the UI.
			gameState.setClockPaused(body.speed() == 0);
			return ResponseEntity.ok(gameState.getClockSnapshot());
		});
	}

	@PostMapping("/pause")
	public Mono<GameState.ClockSnapshot> pause() {
		return Mono.fromSupplier(() -> {
			gameState.setClockPaused(true);
			return gameState.getClockSnapshot();
		});
	}

	@PostMapping("/resume")
	public Mono<GameState.ClockSnapshot> resume() {
		return Mono.fromSupplier(() -> {
			gameState.setClockPaused(false);
			return gameState.getClockSnapshot();
		});
	}

	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<GameState.ClockSnapshot> stream() {
		return engine.clockStream();
	}

	public record SpeedRequest(int speed) {
	}
}

