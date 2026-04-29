package com.dimsumdetours.sim.model;

/**
 * Result of a placement attempt. Sealed so callers must handle both branches.
 */
public sealed interface PlacementResult {

	record Success(Building building, Money newBalance) implements PlacementResult {
	}

	record Failure(PlacementError error) implements PlacementResult {
	}
}

