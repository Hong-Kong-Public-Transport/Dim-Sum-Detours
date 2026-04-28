import {LocalizedString} from "./ingredient.model";

/**
 * Reference to an {@code Operation} id (lower_snake_case, e.g. {@code "mix"}).
 * Operations are JSON-defined under {@code backend/src/main/resources/content/operations/}.
 */
export type OperationReference = string;

export interface Operation {
	readonly id: string;
	readonly displayName: LocalizedString;
	readonly description?: LocalizedString | null;
}

export interface RecipeIngredient {
	readonly ingredientId: string;
	readonly quantity: number;
}

export interface Recipe {
	readonly id: string;
	readonly displayName: LocalizedString;
	readonly inputs: readonly RecipeIngredient[];
	readonly operations: readonly OperationReference[];
	readonly outputs: readonly RecipeIngredient[];
	readonly minimumFactoryTier: number;
	readonly operationDurationMinutes: number;
	readonly tags: readonly string[];
}
