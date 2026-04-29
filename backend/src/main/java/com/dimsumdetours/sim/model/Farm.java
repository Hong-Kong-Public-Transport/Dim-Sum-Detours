package com.dimsumdetours.sim.model;

import java.util.UUID;

public record Farm(
	UUID id,
	double lat,
	double lon,
	String recipeId,
	String outputIngredientId
) implements Building {

	@Override
	public BuildingKind kind() {
		return BuildingKind.FARM;
	}
}

