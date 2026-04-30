package com.dimsumdetours.sim.model;

import java.util.List;
import java.util.UUID;

/**
 * A placed factory. The {@code operations} list is the player-editable execution order;
 * it always starts as a copy of the recipe's operations and can be reordered (Phase 5)
 * but never have entries added or removed — the multiset of ids is invariant.
 */
public record Factory(
	UUID id,
	double lat,
	double lon,
	String recipeId,
	List<String> operations
) implements Building {

	public Factory {
		operations = List.copyOf(operations);
	}

	@Override
	public BuildingKind kind() {
		return BuildingKind.FACTORY;
	}

	/** Convenience for Farms / first placement: defaults the operations list to the recipe's. */
	public static Factory of(UUID id, double lat, double lon, Recipe recipe) {
		return new Factory(id, lat, lon, recipe.id(), recipe.operations());
	}

	/** Returns a copy with the operation list replaced. Throws if the multiset doesn't match. */
	public Factory withOperations(List<String> newOperations) {
		if (!sameMultiset(operations, newOperations)) {
			throw new IllegalArgumentException(
				"New operations must be a permutation of the existing list");
		}
		return new Factory(id, lat, lon, recipeId, newOperations);
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
