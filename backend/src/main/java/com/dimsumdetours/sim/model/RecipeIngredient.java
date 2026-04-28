package com.dimsumdetours.sim.model;

/**
 * A quantity of one ingredient — the input or output line of a {@link Recipe}.
 *
 * @param ingredientId Identifier of the ingredient (must exist in the registry).
 * @param quantity     Number of units consumed (input) or produced (output). Must be {@code > 0}.
 */
public record RecipeIngredient(String ingredientId, int quantity) {

	public RecipeIngredient {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive: " + quantity);
		}
	}
}
