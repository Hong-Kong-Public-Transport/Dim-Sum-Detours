package com.dimsumdetours.engine.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-21 unit tests for
 * {@link TransitInterpolation#interpolatedBusDurationFromArrays}. Closes
 * the second flagged test gap from {@code docs/DISPATCH.md} — the
 * planner-side sparse-feed duration interpolation, extracted from the
 * deleted legacy {@code GtfsMultiLegPlanner} into the framework-free
 * {@link TransitInterpolation} helper.
 *
 * <p>Each fixture builds a synthetic 5-stop trip with cumulative
 * shape-metres at 0/1/2/3/4 km. The {@code scheduledSec} array
 * simulates which stops the GTFS feed published times for; the math
 * derives the boarding→alighting duration from whatever anchors are
 * present.
 */
class RoutePlannerInterpolationTest {

	private static final double[] CUM_5_STOP = {0.0, 1000.0, 2000.0, 3000.0, 4000.0};

	/** All stops scheduled → take the GTFS difference verbatim. */
	@Test
	void usesScheduledTimesWhenAllAnchored() {
		// 0:00 / 0:05 / 0:10 / 0:18 / 0:25 (in seconds-since-midnight).
		int[] sec = {0, 300, 600, 1080, 1500};
		long duration = TransitInterpolation.interpolatedBusDurationFromArrays(
			CUM_5_STOP, sec, /* anchorSec = */ 0, /* boardingIdx = */ 1, /* alightingIdx = */ 3,
			/* busMeters = */ 2000L);

		assertThat(duration).isEqualTo(13L);
	}

	/** Only first + last stop scheduled → middle indices interpolate by shape distance. */
	@Test
	void interpolatesMiddleStopsByShapeDistance() {
		int[] sec = {0, -1, -1, -1, 1200};
		long duration = TransitInterpolation.interpolatedBusDurationFromArrays(
			CUM_5_STOP, sec, 0, 1, 3, 2000L);

		assertThat(duration).isEqualTo(10L);
	}

	/** No scheduled times at all → fall back to BUS_METERS_PER_GAME_MINUTE. */
	@Test
	void fallsBackToBusMetersPerGameMinuteWhenNoAnchors() {
		int[] sec = {-1, -1, -1, -1, -1};
		long duration = TransitInterpolation.interpolatedBusDurationFromArrays(
			CUM_5_STOP, sec, /* anchorSec = */ -1, 1, 3, /* busMeters = */ 2000L);

		assertThat(duration).isBetween(4L, 6L);
	}

	/** Degenerate feed where alight ≤ board after interpolation → defensive
	 * distance-only fallback so the {@code arrivesAt > departsAt} invariant
	 * still holds. */
	@Test
	void clampsToAtLeastOneGameMinute() {
		int[] sec = {0, 600, 600, 600, 600};
		long duration = TransitInterpolation.interpolatedBusDurationFromArrays(
			CUM_5_STOP, sec, 0, 1, 3, /* busMeters = */ 2000L);

		assertThat(duration).isGreaterThanOrEqualTo(1L);
	}

	/** Boarding == alighting → still ≥ 1 game-minute for caller invariants. */
	@Test
	void returnsAtLeastOneEvenForZeroLengthTrip() {
		int[] sec = {0, 300, 600, 900, 1200};
		long duration = TransitInterpolation.interpolatedBusDurationFromArrays(
			CUM_5_STOP, sec, 0, 2, 2, 0L);

		assertThat(duration).isGreaterThanOrEqualTo(1L);
	}
}

