package com.dimsumdetours.sim.model;

/**
 * Phase-8 task 7: the seven progression milestones drawn from the README. Identifiers stay
 * in American English (per the project's identifier convention); player-facing UI strings
 * live in the i18n bundles and use British English.
 *
 * <p>Lives in the framework-agnostic {@code sim/} package — no Spring imports. The tracker
 * that owns the unlocked-set is {@link com.dimsumdetours.sim.state.MilestoneTracker}.
 */
public enum Milestone {
	/** Deliver any dish to any restaurant. The Week-6 tutorial-complete milestone. */
	FIRST_DELIVERY,
	/** Deliver perishable cargo across at least two transfers without spoilage; unlocks the
	 * refrigerated-factory upgrade. */
	COLD_CHAIN,
	/** Every restaurant in one neighbourhood at ≥ 80% reputation for seven game-days. */
	NEIGHBORHOOD_HERO,
	/** Own farm + factory + restaurant across the same recipe chain. */
	VERTICAL_INTEGRATION,
	/** Unlock every base recipe in a single cuisine tree. */
	CUISINE_MASTER,
	/** Use ten distinct GTFS routes simultaneously inside the sliding window. */
	TRANSIT_TYCOON,
	/** Soft-win: hit the cumulative-fulfilled-orders target. */
	CITY_BUILDER
}

