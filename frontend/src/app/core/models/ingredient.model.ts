/** BCP-47 locale tag → translation. {@code en} is the mandatory fallback. */
export type LocalizedString = Readonly<Record<string, string>>;

/**
 * Reference to an {@code IngredientCategory} id (lower_snake_case, e.g. {@code "vegetable"}).
 * Categories are JSON-defined under {@code backend/src/main/resources/content/categories/}.
 */
export type CategoryReference = string;

export interface Ingredient {
	readonly id: string;
	readonly displayName: LocalizedString;
	readonly category: CategoryReference;
	readonly shelfLifeMinutes: number;
	readonly refrigeratable: boolean;
	readonly baseValue: number;
	readonly tags: readonly string[];
}

export interface IngredientCategory {
	readonly id: string;
	readonly displayName: LocalizedString;
	readonly description?: LocalizedString | null;
}
