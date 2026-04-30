package com.dimsumdetours.sim.model;

import com.dimsumdetours.config.GameConstants;

/**
 * Kind of player-built structure. Knowing the kind alone is enough to look up its build
 * cost and daily upkeep (no Spring; just constant lookups).
 */
public enum BuildingKind {
	FARM(GameConstants.FARM_BUILD_COST, GameConstants.FARM_DAILY_UPKEEP),
	FACTORY(GameConstants.FACTORY_BUILD_COST, GameConstants.FACTORY_DAILY_UPKEEP),
	RESTAURANT(GameConstants.RESTAURANT_BUILD_COST, GameConstants.RESTAURANT_DAILY_UPKEEP);

	private final Money buildCost;
	private final Money dailyUpkeep;

	BuildingKind(long buildCost, long dailyUpkeep) {
		this.buildCost = Money.of(buildCost);
		this.dailyUpkeep = Money.of(dailyUpkeep);
	}

	public Money buildCost() {
		return buildCost;
	}

	public Money dailyUpkeep() {
		return dailyUpkeep;
	}
}
