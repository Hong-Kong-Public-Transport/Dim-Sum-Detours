import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {DrawerModule} from "primeng/drawer";
import {TooltipModule} from "primeng/tooltip";

import {Building} from "../../core/model/building.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {LanguageService} from "../../core/service/language.service";
import {localize} from "../../core/i18n/localize";

interface FactoryOperationView {
	readonly index: number;
	readonly id: string;
	readonly name: string;
}

interface FactoryView {
	readonly recipeName: string;
	readonly operations: readonly FactoryOperationView[];
}

/**
 * Right-edge drawer that surfaces the operation chain of the currently-selected factory.
 * Pure presentation: receives the factory + lookup tables as inputs and emits a {@code reorder}
 * event with the new id list — the parent owns the HTTP call.
 */
@Component({
	selector: "app-factory-operations-drawer",
	imports: [TranslocoDirective, ButtonModule, DrawerModule, TooltipModule],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./factory-operations-drawer.component.html",
	styleUrl: "./factory-operations-drawer.component.scss",
})
export class FactoryOperationsDrawerComponent {
	private readonly languageService = inject(LanguageService);

	readonly factory = input<Building | null>(null);
	readonly recipes = input.required<readonly Recipe[]>();
	readonly operations = input.required<readonly Operation[]>();

	readonly closed = output<void>();
	readonly reorder = output<readonly string[]>();

	protected readonly visible = computed(() => this.factory() !== null);

	protected readonly view = computed<FactoryView | null>(() => {
		const factory = this.factory();
		if (!factory || factory.kind !== "FACTORY") {
			return null;
		}
		const language = this.languageService.activeLanguage();
		const recipe = this.recipes().find((candidate) => candidate.id === factory.recipeId);
		const operationLookup = new Map<string, Operation>();
		for (const operation of this.operations()) {
			operationLookup.set(operation.id, operation);
		}
		const operationIds = factory.operations ?? recipe?.operations ?? [];
		return {
			recipeName: recipe ? localize(recipe.displayName, language) : factory.recipeId,
			operations: operationIds.map((operationId, index) => {
				const operation = operationLookup.get(operationId);
				return {
					index,
					id: operationId,
					name: operation ? localize(operation.displayName, language) : operationId,
				};
			}),
		};
	});

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

