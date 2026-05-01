/**
 * Mirrors {@code com.dimsumdetours.sim.model.Milestone}. Identifiers stay in American
 * English to match the backend enum names; the i18n bundle holds the British-English UI
 * strings for each.
 */
export type MilestoneId =
	| "FIRST_DELIVERY"
	| "COLD_CHAIN"
	| "NEIGHBORHOOD_HERO"
	| "VERTICAL_INTEGRATION"
	| "CUISINE_MASTER"
	| "TRANSIT_TYCOON"
	| "CITY_BUILDER";

/** Mirrors {@code GameController.MilestoneDto}. */
export interface Milestone {
	readonly id: MilestoneId;
	readonly unlocked: boolean;
	readonly unlockedAtGameMinutes: number | null;
}

/** Mirrors {@code GameController.MilestonesResponse}. */
export interface MilestonesResponse {
	readonly milestones: readonly Milestone[];
	readonly fulfilledCount: number;
}

/** Mirrors {@code com.dimsumdetours.sim.model.MilestoneEvent}. */
export interface MilestoneEvent {
	readonly milestone: MilestoneId;
	readonly gameMinutes: number;
}

