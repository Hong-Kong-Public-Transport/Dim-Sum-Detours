/** Mirrors {@code com.dimsumdetours.sim.model.BuildingKind}. */
export type BuildingKind = "FARM" | "FACTORY" | "RESTAURANT";

/** Mirrors {@code com.dimsumdetours.api.GameController.BuildingDto}. */
export interface Building {
	readonly id: string;
	readonly kind: BuildingKind;
	readonly lat: number;
	readonly lon: number;
	readonly recipeId: string;
	readonly outputIngredientId?: string | null;
	/**
	 * Phase 5: per-factory operation chain. For farms this mirrors the recipe's single
	 * harvest/grow step (read-only). For factories this can be reordered by the player.
	 */
	readonly operations?: readonly string[];
	/** Phase 6: 0.0–1.0 reputation. Populated only for {@code kind === "RESTAURANT"}. */
	readonly reputation?: number | null;
	/** Phase 7: true if the restaurant has been auto-closed because reputation fell below
	 * {@code RESTAURANT_CLOSE_REPUTATION_THRESHOLD}. Populated only for restaurants. */
	readonly closed?: boolean | null;
	/** Phase 6: id of the {@code RestaurantTemplate} this restaurant was placed from. */
	readonly templateId?: string | null;
	/**
	 * Phase 8 production cycle: the absolute game-minute the current cycle started.
	 * Combined with {@link cycleDurationGameMinutes} this lets the frontend render a live
	 * progress ring around the marker without polling the backend per tick. {@code -1} (or
	 * undefined) for buildings that don't produce — Restaurants.
	 */
	readonly cycleStartedAtGameMinutes?: number | null;
	/** Phase 8: cycle length in game-minutes. {@code 0} (or undefined) for non-producing buildings. */
	readonly cycleDurationGameMinutes?: number | null;
	/** Phase 8: lifetime number of completed production cycles. */
	readonly producedUnits?: number | null;
	/** Phase-8 task 6: refrigeration upgrade flag. Populated only for factories. When true,
	 * cargo dispatched from this factory ignores the spoilage timer. */
	readonly refrigerated?: boolean | null;
	/**
	 * Phase-10: per-ingredient input stockpile for factories. Keys are ingredient ids,
	 * values are integer quantities. {@code null} or undefined for non-factories. The
	 * factory's recipe is only allowed to complete a cycle (and increment
	 * {@link producedUnits}) when this map holds at least one full set of the recipe's
	 * required {@code RecipeIngredient} inputs.
	 */
	readonly inputStockpile?: Readonly<Record<string, number>> | null;
	/**
	 * Phase-12: a factory is "stalled" iff its input stockpile can't cover one full
	 * production cycle for the configured recipe. Drives the {@code .stalled} class on
	 * the building marker (greyscale + ring hidden) so the player sees the starved
	 * factories at a glance. Null for farms / restaurants.
	 */
	readonly stalled?: boolean | null;
	/**
	 * Phase-13: lifetime fulfilled-order count for restaurants (FULFILLED + LATE).
	 * Null/undefined for farms / factories. Surfaced in the restaurant info drawer
	 * as a concrete throughput signal alongside the reputation bar.
	 */
	readonly fulfilledOrders?: number | null;
}

export interface PlaceBuildingRequest {
	readonly kind: BuildingKind;
	readonly lat: number;
	readonly lon: number;
	readonly recipeId: string;
	readonly templateId?: string | null;
}

export interface BalanceResponse {
	readonly amount: number;
}

/**
 * Mirrors {@code PlaceBuildingResponse}: success populates {@code building} + {@code balanceAmount};
 * failure populates {@code error}, optionally with {@code requiredAmount} and the current balance.
 */
export interface PlaceBuildingResponse {
	readonly building: Building | null;
	readonly balanceAmount: number | null;
	readonly error: string | null;
	readonly requiredAmount: number | null;
}
