/** Mirrors {@code com.dimsumdetours.sim.model.RestaurantTemplate}. */
export interface RestaurantTemplate {
	readonly id: string;
	readonly displayName: Readonly<Record<string, string>>;
	readonly acceptedRecipeIds: readonly string[];
	readonly basePatienceMinutes: number;
	readonly basePayout: number;
	readonly tags?: readonly string[] | null;
}

