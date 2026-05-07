package com.dimsumdetours.gtfs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-21 unit tests for the sparse-feed stop-time interpolation that
 * powers both {@link TransitSnapshotService} and (indirectly) the
 * {@code GtfsMultiLegPlanner} duration fallback. Closes one of the test
 * gaps tracked under "Active backlog" in {@code docs/ROADMAP.md}.
 */
class TransitSnapshotInterpolationTest {

	/** Feed scheduled every stop → arrays are unchanged. */
	@Test
	void preservesFullySchedluedTrip() {
		long[] arrivals = {0L, 5L, 10L, 18L};
		long[] departures = {0L, 5L, 10L, 18L};
		int[] indices = {0, 1, 2, 3};
		List<double[]> shape = straightShape(4, 1000.0);

		TransitSnapshotService.interpolateMissingStopTimes(arrivals, departures, indices, shape);

		assertThat(arrivals).containsExactly(0L, 5L, 10L, 18L);
		assertThat(departures).containsExactly(0L, 5L, 10L, 18L);
	}

	/** Feed with only first + last stop scheduled → middle stops fill by
	 * piecewise-linear interpolation against cumulative shape distance. */
	@Test
	void fillsMiddleStopsByShapeDistance() {
		long[] arrivals = {0L, -1L, -1L, 30L};
		long[] departures = {0L, -1L, -1L, 30L};
		int[] indices = {0, 1, 2, 3};
		// Even-spaced shape vertices, 1 km apart → cumulative 0/1k/2k/3k.
		List<double[]> shape = straightShape(4, 1000.0);

		TransitSnapshotService.interpolateMissingStopTimes(arrivals, departures, indices, shape);

		// Linear in cumulative-meters → expect roughly 0/10/20/30.
		assertThat(arrivals[0]).isEqualTo(0L);
		assertThat(arrivals[3]).isEqualTo(30L);
		assertThat(arrivals[1]).isBetween(8L, 12L);
		assertThat(arrivals[2]).isBetween(18L, 22L);
		// Monotonic non-decreasing.
		for (int i = 1; i < arrivals.length; i++) {
			assertThat(arrivals[i]).isGreaterThanOrEqualTo(arrivals[i - 1]);
		}
	}

	/** Feed with NO scheduled times at all → synthesise from
	 * {@code cumulativeMeters / BUS_METERS_PER_GAME_MINUTE} so the route
	 * is still drawable + plannable. */
	@Test
	void synthesisesFromShapeWhenAllUnscheduled() {
		long[] arrivals = {-1L, -1L, -1L, -1L};
		long[] departures = {-1L, -1L, -1L, -1L};
		int[] indices = {0, 1, 2, 3};
		List<double[]> shape = straightShape(4, 1000.0); // total 3 km.

		TransitSnapshotService.interpolateMissingStopTimes(arrivals, departures, indices, shape);

		assertThat(arrivals[0]).isEqualTo(0L);
		// 3 km / 420 m·min⁻¹ ≈ 7.14 game-min, so last stop ≥ first.
		assertThat(arrivals[3]).isGreaterThan(arrivals[0]);
		// Monotonic non-decreasing.
		for (int i = 1; i < arrivals.length; i++) {
			assertThat(arrivals[i]).isGreaterThanOrEqualTo(arrivals[i - 1]);
		}
	}

	/** Disagreeing anchors → final monotonic sweep clamps. */
	@Test
	void enforcesMonotonicNonDecreasing() {
		long[] arrivals = {10L, 5L, -1L, 20L};
		long[] departures = {10L, 5L, -1L, 20L};
		int[] indices = {0, 1, 2, 3};
		List<double[]> shape = straightShape(4, 1000.0);

		TransitSnapshotService.interpolateMissingStopTimes(arrivals, departures, indices, shape);

		for (int i = 1; i < arrivals.length; i++) {
			assertThat(arrivals[i]).isGreaterThanOrEqualTo(arrivals[i - 1]);
		}
	}

	/** Build {@code n} evenly-spaced lat-points along a meridian, each
	 * separated by {@code spacingMetres}. Latitude-only deltas keep the
	 * haversine math trivial (~111 km / degree). */
	private static List<double[]> straightShape(int n, double spacingMetres) {
		double degPerMetre = 1.0 / 111_000.0;
		List<double[]> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(new double[]{i * spacingMetres * degPerMetre, 0.0});
		}
		return out;
	}
}

