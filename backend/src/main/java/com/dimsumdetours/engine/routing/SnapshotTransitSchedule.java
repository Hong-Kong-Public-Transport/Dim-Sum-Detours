package com.dimsumdetours.engine.routing;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.gtfs.TransitSnapshotService;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.TransitSchedule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.OptionalLong;

/**
 * Phase-21 Spring adapter: turns a built {@link TransitSnapshotService}
 * into the {@link TransitSchedule} SPI that {@link GameState} consumes.
 * Wired into {@code GameState} at boot via
 * {@link GameState#setTransitSchedule}.
 *
 * <p>Ambient-run model:
 * <pre>
 *   route R departs first stop every BUS_HEADWAY_GAME_MINUTES = H game-min.
 *   run k departed at k·H; reaches stop[i] at  k·H + relativeArrival[i].
 * </pre>
 *
 * <p>For {@link #findRunCrossingStop}, given a window
 * {@code (windowStart, windowEnd]}: find the smallest non-negative {@code k}
 * such that {@code k·H + rel[i] ∈ (windowStart, windowEnd]}. Solve for
 * {@code k}: {@code k ∈ ((windowStart − rel[i]) / H, (windowEnd − rel[i]) / H]};
 * the smallest valid integer is {@code ceil((windowStart − rel[i] + 1) / H)},
 * clamped at 0. If that integer's arrival is also {@code ≤ windowEnd}, we
 * have a hit; otherwise no run crossed the stop in this window.
 *
 * <p>For {@link #arrivalAtStop}, given a known run {@code (departureOffset,
 * routeId)}: return {@code departureOffset + relativeArrival[i]} where
 * {@code i} is the stop's index on this route's representative trip.
 *
 * <p>Unknown route ⇒ "no run / never arrives". Unknown stop on a known route
 * ⇒ same. The boarding state machine treats those as silent skips, which is
 * fine — the dispatcher will never have produced a {@code RoutePlan.Transit}
 * for an unknown (route, stop) pair in the first place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotTransitSchedule implements TransitSchedule {

	private final TransitSnapshotService snapshotService;
	private final GameState gameState;

	@PostConstruct
	void wireIntoGameState() {
		gameState.setTransitSchedule(this);
		log.info("SnapshotTransitSchedule wired into GameState (snapshot present: {})",
			snapshotService.getSnapshot().isPresent());
	}

	@Override
	public @Nullable RunArrival findRunCrossingStop(
		String routeId, String stopId,
		long windowStartExclusive, long windowEndInclusive
	) {
		long h = GameConstants.BUS_HEADWAY_GAME_MINUTES;
		if (h <= 0L) {
			return null;
		}
		TransitSnapshotService.@Nullable Snapshot snapshot = snapshotService.getSnapshot().orElse(null);
		if (snapshot == null) {
			return null;
		}
		// A route can have multiple direction entries; either may serve the
		// boarding stop. Walk all of them and keep the earliest hit so a
		// (route, stop) pair that's served by both directions still produces
		// a deterministic answer (the soonest run wins).
		long bestArrival = Long.MAX_VALUE;
		long bestDeparture = 0L;
		int bestType = 0;
		boolean anyHit = false;
		for (TransitSnapshotService.TransitRoute route : snapshot.routes()) {
			if (!routeId.equals(route.routeId())) {
				continue;
			}
			int stopIdx = route.stopIds().indexOf(stopId);
			if (stopIdx < 0 || stopIdx >= route.stopArrivalGameMinutes().length) {
				continue;
			}
			long rel = route.stopArrivalGameMinutes()[stopIdx];
			if (rel < 0L) {
				// Phase-20 interpolation should have filled every cell;
				// this is a defensive fallback for routes whose snapshot
				// pre-dates that interpolation pass.
				continue;
			}
			// Smallest non-negative k with k*H + rel > windowStartExclusive.
			// k > (windowStartExclusive - rel) / H, i.e. k = floor(...) + 1
			// when the division is exact; otherwise k = ceil(...).
			long minK;
			long numerator = windowStartExclusive - rel;
			if (numerator < 0L) {
				minK = 0L;
			} else {
				minK = numerator / h + 1L;
			}
			long candidateArrival = minK * h + rel;
			if (candidateArrival <= windowEndInclusive && candidateArrival < bestArrival) {
				bestArrival = candidateArrival;
				bestDeparture = minK * h;
				bestType = route.type();
				anyHit = true;
			}
		}
		if (!anyHit) {
			return null;
		}
		return new RunArrival(bestDeparture, bestType, bestArrival);
	}

	@Override
	public OptionalLong arrivalAtStop(
		String routeId, long departureOffsetGameMinutes, String stopId
	) {
		TransitSnapshotService.@Nullable Snapshot snapshot = snapshotService.getSnapshot().orElse(null);
		if (snapshot == null) {
			return OptionalLong.empty();
		}
		// Same direction-collapse as findRunCrossingStop: pick the
		// earliest arrival among matching directions so the answer is
		// deterministic.
		long best = Long.MAX_VALUE;
		boolean any = false;
		for (TransitSnapshotService.TransitRoute route : snapshot.routes()) {
			if (!routeId.equals(route.routeId())) {
				continue;
			}
			int stopIdx = route.stopIds().indexOf(stopId);
			if (stopIdx < 0 || stopIdx >= route.stopArrivalGameMinutes().length) {
				continue;
			}
			long rel = route.stopArrivalGameMinutes()[stopIdx];
			if (rel < 0L) {
				continue;
			}
			long absolute = departureOffsetGameMinutes + rel;
			if (absolute < best) {
				best = absolute;
				any = true;
			}
		}
		return any ? OptionalLong.of(best) : OptionalLong.empty();
	}
}

