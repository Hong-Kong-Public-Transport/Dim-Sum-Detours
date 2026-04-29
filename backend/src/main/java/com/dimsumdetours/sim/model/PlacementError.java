package com.dimsumdetours.sim.model;

/**
 * Discriminated reason a {@code GameState.placeBuilding(...)} call failed. Lets the API layer
 * map deterministically to HTTP status codes without exception plumbing.
 */
public enum PlacementError {
	INSUFFICIENT_FUNDS,
	UNKNOWN_RECIPE,
	RECIPE_TIER_TOO_HIGH,
	INVALID_COORDINATES,
	INVALID_PLACEMENT_LOCATION,
	RECIPE_HAS_NO_OUTPUTS
}

