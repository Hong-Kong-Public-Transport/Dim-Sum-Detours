import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";

import {localize} from "../../core/i18n/localize";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {LanguageService} from "../../core/service/language.service";

interface RecipeLine {
	readonly ingredientId: string;
	readonly text: string;
}

interface RecipeTileView {
	readonly id: string;
	readonly name: string;
	readonly inputs: readonly RecipeLine[];
	readonly outputs: readonly RecipeLine[];
	readonly operations: string;
}

/**
 * Reusable card showing a recipe's inputs → outputs and its operation chain. Used by:
 *
 * <ul>
 *   <li>The sidebar recipe list (read-only, dense).</li>
 *   <li>The recipe-picker modal opened by Place Farm / Place Factory (clickable).</li>
 *   <li>The farm and factory drawers (read-only, in-context).</li>
 * </ul>
 *
 * <p>Pure presentation: receives the recipe + lookup tables, emits {@code picked} when
 * {@code clickable} and the player activates the tile.
 */
@Component({
	selector: "app-recipe-tile",
	imports: [TranslocoDirective],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./recipe-tile.component.html",
	styleUrl: "./recipe-tile.component.scss",
	host: {
		"[class.app-recipe-tile-clickable]": "clickable()",
	},
})
export class RecipeTileComponent {
	private readonly languageService = inject(LanguageService);

	readonly recipe = input.required<Recipe>();
	readonly ingredients = input.required<readonly Ingredient[]>();
	readonly operations = input<readonly Operation[]>([]);
	/** Render the tile as an interactive button (used by the picker dialog). */
	readonly clickable = input<boolean>(false);

	readonly picked = output<string>();

	protected readonly view = computed<RecipeTileView>(() => {
		const language = this.languageService.activeLanguage();
		const ingredientLookup = new Map(this.ingredients().map((ingredient) => [ingredient.id, ingredient]));
		const operationLookup = new Map(this.operations().map((operation) => [operation.id, operation]));
		const recipe = this.recipe();

		const formatLine = (ingredientId: string, quantity: number): RecipeLine => {
			const ingredient = ingredientLookup.get(ingredientId);
			const name = ingredient ? localize(ingredient.displayName, language) : ingredientId;
			return {ingredientId, text: `${quantity}× ${name}`};
		};

		const operationLabels = recipe.operations.map((operationId) => {
			const operation = operationLookup.get(operationId);
			return operation ? localize(operation.displayName, language) : operationId;
		});

		return {
			id: recipe.id,
			name: localize(recipe.displayName, language),
			inputs: recipe.inputs.map((line) => formatLine(line.ingredientId, line.quantity)),
			outputs: recipe.outputs.map((line) => formatLine(line.ingredientId, line.quantity)),
			operations: operationLabels.join(" → "),
		};
	});

	protected onActivate(): void {
		if (this.clickable()) {
			this.picked.emit(this.recipe().id);
		}
	}
}

