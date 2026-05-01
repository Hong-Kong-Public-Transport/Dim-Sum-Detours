package com.dimsumdetours.sim.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A placed factory. The {@code operations} list is the player-editable execution order;
 * it always starts as a copy of the recipe's operations and can be reordered (Phase 5)
 * but never have entries added or removed — the multiset of ids is invariant.
 *
 * <p>Phase-8 production: same cycle/produced fields as {@link Farm}. The cycle duration
 * defaults to {@code recipe.operationDurationMinutes * max(1, operations.size())} so a
 * longer chain takes proportionally longer.
 *
 * <p>Phase-10 inputs: factories now hold an {@code inputStockpile} keyed by
 * {@code ingredientId}. {@link com.dimsumdetours.sim.state.GameState#advanceProduction()}
 * only completes a cycle when the stockpile holds at least one full set of the recipe's
 * required {@link RecipeIngredient inputs}; on completion those quantities are decremented
 * and {@code producedUnits} is incremented. The map is empty for a fresh factory and is
 * grown by farm→factory delivery walkers via
 * {@code GameState#tryDeliverInputToFactory(UUID, String, int)}.
 */
public record Factory(
	UUID id,
	double lat,
	double lon,
	String recipeId,
	List<String> operations,
	long cycleStartedAtGameMinutes,
	long cycleDurationGameMinutes,
	long producedUnits,
	boolean refrigerated,
	Map<String, Integer> inputStockpile
) implements Building {

	public Factory {
		operations = List.copyOf(operations);
		inputStockpile = Map.copyOf(inputStockpile);
		if (cycleDurationGameMinutes <= 0) {
			throw new IllegalArgumentException(
				"cycleDurationGameMinutes must be positive, got " + cycleDurationGameMinutes);
		}
		if (producedUnits < 0) {
			throw new IllegalArgumentException("producedUnits cannot be negative, got " + producedUnits);
		}
		for (Map.Entry<String, Integer> entry : inputStockpile.entrySet()) {
			if (entry.getValue() == null || entry.getValue() < 0) {
				throw new IllegalArgumentException(
					"inputStockpile values must be non-negative, got "
						+ entry.getKey() + "=" + entry.getValue());
			}
		}
	}

	@Override
	public BuildingKind kind() {
		return BuildingKind.FACTORY;
	}

	/** Convenience: a freshly-placed factory has an empty stockpile and zero produced
	 * units. The Phase-8 "starter unit" courtesy was removed in Phase-11 — players
	 * objected that it implied production happens out of thin air. Now the player must
	 * actually feed the factory inputs (via farm→factory walkers) before any output
	 * leaves the gate, mirroring the realism the game's conveyor-belt inspirations rely on. */
	public static Factory of(
		UUID id, double lat, double lon, Recipe recipe,
		long cycleStartedAtGameMinutes, long cycleDurationGameMinutes
	) {
		return new Factory(
			id, lat, lon, recipe.id(), recipe.operations(),
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, 0L, false, Map.of());
	}

	/** Returns a copy with the operation list replaced. Throws if the multiset doesn't match. */
	public Factory withOperations(List<String> newOperations) {
		if (!sameMultiset(operations, newOperations)) {
			throw new IllegalArgumentException(
				"New operations must be a permutation of the existing list");
		}
		return new Factory(id, lat, lon, recipeId, newOperations,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, producedUnits, refrigerated,
			inputStockpile);
	}

	/** Returns a copy with the production cycle advanced by {@code completedCycles} units. */
	public Factory withProducedAdvance(long completedCycles, long newCycleStartedAtGameMinutes) {
		if (completedCycles <= 0) {
			return this;
		}
		return new Factory(
			id, lat, lon, recipeId, operations,
			newCycleStartedAtGameMinutes, cycleDurationGameMinutes,
			producedUnits + completedCycles, refrigerated, inputStockpile);
	}

	/**
	 * Returns a copy with one finished unit consumed from the stockpile, or empty if none
	 * available. Mirrors {@link Farm#withProducedUnitConsumed()} for the delivery dispatcher.
	 */
	public java.util.Optional<Factory> withProducedUnitConsumed() {
		if (producedUnits <= 0) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Factory(
			id, lat, lon, recipeId, operations,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes,
			producedUnits - 1, refrigerated, inputStockpile));
	}

	/**
	 * Phase-8 task 6: returns a copy with the refrigerated upgrade flag flipped on. Idempotent
	 * — calling it on an already-refrigerated factory returns {@code this}. The upgrade pauses
	 * spoilage on cargo dispatched from this factory: see the frontend
	 * {@code DeliveryService#computeSpoilageDeadline} for the in-transit handling.
	 */
	public Factory withRefrigerated() {
		if (refrigerated) {
			return this;
		}
		return new Factory(
			id, lat, lon, recipeId, operations,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, producedUnits, true,
			inputStockpile);
	}

	/**
	 * Phase-10: returns a copy with {@code quantity} units of {@code ingredientId} added to
	 * the input stockpile. Used by the farm→factory delivery walker on arrival.
	 */
	public Factory withInputDelivered(String ingredientId, int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, got " + quantity);
		}
		Map<String, Integer> next = new HashMap<>(inputStockpile);
		next.merge(ingredientId, quantity, Integer::sum);
		return new Factory(
			id, lat, lon, recipeId, operations,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, producedUnits, refrigerated,
			next);
	}

	/**
	 * Phase-10: true iff every required input quantity is present in the stockpile.
	 * Used by {@code GameState#advanceProduction} to gate cycle completion on real inputs.
	 */
	public boolean hasInputsFor(Recipe recipe) {
		for (RecipeIngredient input : recipe.inputs()) {
			Integer have = inputStockpile.get(input.ingredientId());
			if (have == null || have < input.quantity()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Phase-10: returns a copy with one full input set decremented from the stockpile.
	 * Throws if any required input is missing — callers must guard with {@link #hasInputsFor}.
	 */
	public Factory withInputsConsumed(Recipe recipe) {
		Map<String, Integer> next = new HashMap<>(inputStockpile);
		for (RecipeIngredient input : recipe.inputs()) {
			Integer have = next.get(input.ingredientId());
			if (have == null || have < input.quantity()) {
				throw new IllegalStateException("Missing input " + input.ingredientId()
					+ " — caller forgot to check hasInputsFor()");
			}
			int remaining = have - input.quantity();
			if (remaining == 0) {
				next.remove(input.ingredientId());
			} else {
				next.put(input.ingredientId(), remaining);
			}
		}
		return new Factory(
			id, lat, lon, recipeId, operations,
			cycleStartedAtGameMinutes, cycleDurationGameMinutes, producedUnits, refrigerated,
			next);
	}

	private static boolean sameMultiset(List<String> a, List<String> b) {
		if (a.size() != b.size()) {
			return false;
		}
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (String item : a) {
			counts.merge(item, 1, Integer::sum);
		}
		for (String item : b) {
			Integer current = counts.get(item);
			if (current == null || current == 0) {
				return false;
			}
			counts.put(item, current - 1);
		}
		return true;
	}
}
