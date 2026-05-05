package com.dimsumdetours.sim.model;

import java.util.UUID;

/**
 * A placed farm. Produces one unit of {@link #outputIngredientId} every
 * {@link #cycleDurationGameMinutes} game-minutes, starting from
 * {@link #cycleStartedAtGameMinutes}. The simulation engine advances
 * {@link #producedUnits} on each cycle boundary; the frontend reads the same fields
 * to render the production progress ring around the marker icon.
 */
public record Farm(
	UUID id,
	double lat,
	double lon,
	String recipeId,
	String outputIngredientId,
	long cycleStartedAtGameMinutes,
	long cycleDurationGameMinutes,
	long producedUnits
) implements Building {

	public Farm {
		if (cycleDurationGameMinutes <= 0) {
			throw new IllegalArgumentException(
				"cycleDurationGameMinutes must be positive, got " + cycleDurationGameMinutes);
		}
		if (producedUnits < 0) {
			throw new IllegalArgumentException("producedUnits cannot be negative, got " + producedUnits);
		}
	}

	@Override
	public BuildingKind kind() {
		return BuildingKind.FARM;
	}

	/**
	 * Convenience for placement. Phase-11: a freshly-placed farm starts with an empty
	 * stockpile — players objected to the Phase-8 "starter unit" courtesy that made
	 * production look like it happened out of thin air. The first cycle still completes
	 * after {@code cycleDurationGameMinutes}, so on a 10-game-minute farm at 1× speed the
	 * very first walker leaves about 10 real seconds after placement.
	 */
	public static Farm of(
		UUID id, double lat, double lon, String recipeId, String outputIngredientId,
		long cycleStartedAtGameMinutes, long cycleDurationGameMinutes
	) {
		return new Farm(
			id, lat, lon, recipeId, outputIngredientId,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, 0L);
	}

	/** Returns a copy with the production cycle advanced by {@code completedCycles} units. */
	public Farm withProducedAdvance(long completedCycles, long newCycleStartedAtGameMinutes) {
		if (completedCycles <= 0) {
			return this;
		}
		return new Farm(
			id, lat, lon, recipeId, outputIngredientId,
			newCycleStartedAtGameMinutes, cycleDurationGameMinutes,
			producedUnits + completedCycles);
	}

	/**
	 * Returns a copy with one finished unit consumed from the stockpile, or empty if none
	 * available. Used by the delivery dispatcher (Phase 8 task 4) to gate van departure on
	 * actual produced inventory rather than letting empty farms supply orders.
	 */
	public java.util.Optional<Farm> withProducedUnitConsumed() {
		return withProducedUnitsConsumed(1);
	}

	/**
	 * Phase-17: atomic batch debit. Returns a copy with {@code n} finished units
	 * consumed, or empty if the stockpile holds fewer than {@code n}. The dispatcher
	 * uses this to ship a full robot batch in one go rather than launching N robots
	 * each carrying one unit.
	 */
	public java.util.Optional<Farm> withProducedUnitsConsumed(int n) {
		if (n <= 0 || producedUnits < n) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Farm(
			id, lat, lon, recipeId, outputIngredientId,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes,
			producedUnits - n));
	}
}
