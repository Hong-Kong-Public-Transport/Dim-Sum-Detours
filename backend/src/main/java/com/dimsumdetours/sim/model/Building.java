package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A player-built structure on the map. Phase 3 ships {@link Farm} and {@link Factory}; later
 * phases will add {@code Restaurant}, {@code Warehouse}, etc.
 *
 * <p>Sealed so the engine can exhaustively handle all variants in pattern-matching switches.
 */
public sealed interface Building permits Farm, Factory {

	UUID id();

	BuildingKind kind();

	double lat();

	double lon();

	/** Recipe this building runs (Farms produce raw ingredient; Factories chain ops). */
	String recipeId();

	/**
	 * For Farms: the raw ingredient produced (derived from the recipe's first output at
	 * placement time). {@code null} for Factories.
	 */
	default @Nullable String outputIngredientId() {
		return null;
	}
}

