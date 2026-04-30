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
	/** Phase 6: id of the {@code RestaurantTemplate} this restaurant was placed from. */
	readonly templateId?: string | null;
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
