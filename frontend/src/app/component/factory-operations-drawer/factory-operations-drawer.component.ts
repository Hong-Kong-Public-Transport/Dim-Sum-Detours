import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {DrawerModule} from "primeng/drawer";
import {TooltipModule} from "primeng/tooltip";

import {localize} from "../../core/i18n/localize";
import {Building} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {LanguageService} from "../../core/service/language.service";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";

interface FactoryOperationView {
	readonly index: number;
	readonly id: string;
	readonly name: string;
}

interface FactoryInputView {
	readonly id: string;
	readonly name: string;
	readonly have: number;
	readonly need: number;
}

interface FactoryView {
	readonly recipe: Recipe | null;
	readonly operations: readonly FactoryOperationView[];
	readonly producedUnits: number;
	readonly refrigerated: boolean;
	/** Phase-10: per-input ingredient stockpile vs. the recipe's required quantity. Empty
	 * for recipes without inputs (no-input farms aren't shown here anyway). */
	readonly inputs: readonly FactoryInputView[];
	/** Convenience flag — true when the recipe has inputs but every required ingredient
	 * is below its per-cycle quantity. The template surfaces a "stalled — awaiting inputs"
	 * hint so the player understands why the production ring isn't ticking. */
	readonly stalled: boolean;
}

/**
 * Right-edge drawer that surfaces the operation chain of the currently-selected factory.
 * Pure presentation: receives the factory + lookup tables as inputs and emits a {@code reorder}
 * event with the new id list — the parent owns the HTTP call.
 */
@Component({
	selector: "app-factory-operations-drawer",
	imports: [ButtonModule, DrawerModule, RecipeTileComponent, TooltipModule, TranslocoDirective],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./factory-operations-drawer.component.html",
	styleUrl: "./factory-operations-drawer.component.scss",
})
export class FactoryOperationsDrawerComponent {
	private readonly languageService = inject(LanguageService);

	readonly factory = input<Building | null>(null);
	readonly recipes = input.required<readonly Recipe[]>();
	readonly ingredients = input.required<readonly Ingredient[]>();
	readonly operations = input.required<readonly Operation[]>();

	readonly closed = output<void>();
	readonly reorder = output<readonly string[]>();
	/** Phase-9 beta-polish: player asked to spend the refrigeration upgrade fee on this
	 * factory. The parent component owns the HTTP call so this drawer stays presentation-only. */
	readonly refrigerateRequested = output<void>();

	protected readonly visible = computed(() => this.factory() !== null);

	protected readonly view = computed<FactoryView | null>(() => {
		const factory = this.factory();
		if (!factory || factory.kind !== "FACTORY") {
			return null;
		}
		const language = this.languageService.activeLanguage();
		const recipe = this.recipes().find((candidate) => candidate.id === factory.recipeId) ?? null;
		const operationLookup = new Map<string, Operation>();
		for (const operation of this.operations()) {
			operationLookup.set(operation.id, operation);
		}
		const operationIds = factory.operations ?? recipe?.operations ?? [];
		const ingredientLookup = new Map<string, Ingredient>();
		for (const ingredient of this.ingredients()) {
			ingredientLookup.set(ingredient.id, ingredient);
		}
		const stockpile = factory.inputStockpile ?? {};
		const inputs: FactoryInputView[] = (recipe?.inputs ?? []).map((input) => {
			const ingredient = ingredientLookup.get(input.ingredientId);
			return {
				id: input.ingredientId,
				name: ingredient ? localize(ingredient.displayName, language) : input.ingredientId,
				have: stockpile[input.ingredientId] ?? 0,
				need: input.quantity,
			};
		});
		const stalled = inputs.length > 0 && inputs.every((entry) => entry.have < entry.need);
		return {
			recipe,
			operations: operationIds.map((operationId, index) => {
				const operation = operationLookup.get(operationId);
				return {
					index,
					id: operationId,
					name: operation ? localize(operation.displayName, language) : operationId,
				};
			}),
			producedUnits: factory.producedUnits ?? 0,
			refrigerated: factory.refrigerated === true,
			inputs,
			stalled,
		};
	});

	protected requestRefrigerate(): void {
		this.refrigerateRequested.emit();
	}

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.closed.emit();
		}
	}

	protected moveOperation(index: number, delta: number): void {
		const view = this.view();
		if (!view) {
			return;
		}
		const target = index + delta;
		if (target < 0 || target >= view.operations.length) {
			return;
		}
		const reordered = view.operations.map((operation) => operation.id);
		const [moved] = reordered.splice(index, 1);
		reordered.splice(target, 0, moved);
		this.reorder.emit(reordered);
	}
}
