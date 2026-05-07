package com.dimsumdetours.engine.routing;

import com.dimsumdetours.config.GameConstants;

/**
 * Phase-21: pure-arithmetic helpers for deriving GTFS bus-leg durations
 * when the feed is sparse (some or all stop-times missing). Extracted
 * from the legacy {@code GtfsMultiLegPlanner} so the planning logic
 * can move into {@link RoutePlanner} without dragging the GTFS DAO
 * type along — and so the unit tests stay framework-free.
 *
 * <p>The math is straight-line-distance interpolation against per-stop
 * cumulative shape distance: every anchored stop time becomes a
 * {@code (cum[i], time[i])} pair; the boarding and alighting times
 * are linearly interpolated against {@code cum[boardingIdx]} /
 * {@code cum[alightingIdx]}. When the feed has no anchored times at
 * all we fall back to the {@link GameConstants#BUS_METERS_PER_GAME_MINUTE}
 * cruise estimate against the bus polyline length.
 */
public final class TransitInterpolation {

	private TransitInterpolation() {}

	/**
	 * Compute boarding→alighting duration in game-minutes from the
	 * trip's per-stop cumulative-shape-metres + scheduled-seconds
	 * arrays. {@code -1} sentinel cells in {@code scheduledSec} are
	 * treated as missing and interpolated.
	 *
	 * <p>Always returns {@code ≥ 1} so the {@code arrivesAt > departsAt}
	 * invariant downstream cannot underflow on a degenerate feed.
	 *
	 * @param cum            per-stop cumulative shape-metres along the trip.
	 * @param scheduledSec   per-stop seconds-since-midnight, {@code -1} for
	 *                       stops with no scheduled time in the feed.
	 * @param anchorSec      reference second (typically the first stop's
	 *                       scheduled time); {@code -1} means none.
	 * @param boardingIdx    boarding stop index in {@code cum} / {@code scheduledSec}.
	 * @param alightingIdx   alighting stop index.
	 * @param busMeters      length of the bus polyline (cargo path) in metres,
	 *                       used for the no-anchor fallback.
	 */
	public static long interpolatedBusDurationFromArrays(
		double[] cum, int[] scheduledSec, int anchorSec,
		int boardingIdx, int alightingIdx, long busMeters
	) {
		int n = cum.length;
		if (anchorSec < 0) {
			return Math.max(1L, Math.round(
				busMeters / GameConstants.BUS_METERS_PER_GAME_MINUTE));
		}
		double[] anchorCum = new double[n];
		double[] anchorTime = new double[n];
		int aCount = 0;
		for (int i = 0; i < n; i++) {
			int sec = scheduledSec[i];
			if (sec < 0) continue;
			anchorCum[aCount] = cum[i];
			anchorTime[aCount] = (sec - anchorSec) / 60.0;
			aCount++;
		}
		double boardTime = interpolateAt(anchorCum, anchorTime, aCount, cum[boardingIdx]);
		double alightTime = interpolateAt(anchorCum, anchorTime, aCount, cum[alightingIdx]);
		long result = Math.round(alightTime - boardTime);
		if (result <= 0) {
			result = Math.round(busMeters / GameConstants.BUS_METERS_PER_GAME_MINUTE);
		}
		return Math.max(1L, result);
	}

	/** Piecewise-linear interpolation of {@code (cum[k], time[k])} at
	 * {@code targetCum}. Extrapolates flat past the ends. */
	private static double interpolateAt(double[] cum, double[] time, int count, double targetCum) {
		if (count == 0) return 0.0;
		if (targetCum <= cum[0]) return time[0];
		if (targetCum >= cum[count - 1]) return time[count - 1];
		for (int k = 0; k < count - 1; k++) {
			if (targetCum <= cum[k + 1]) {
				double span = cum[k + 1] - cum[k];
				if (span <= 0.0) return time[k];
				double t = (targetCum - cum[k]) / span;
				return time[k] + (time[k + 1] - time[k]) * t;
			}
		}
		return time[count - 1];
	}
}

