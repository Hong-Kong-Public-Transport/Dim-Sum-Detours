import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {DrawerModule} from "primeng/drawer";

import {localize} from "../../core/i18n/localize";
import {Building} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {ClockService} from "../../core/service/clock.service";
import {LanguageService} from "../../core/service/language.service";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";

interface FarmView {
	readonly recipe: Recipe;
	readonly outputIngredientName: string;
	readonly ratePerHour: number;
	readonly producedUnits: number;
}

/**
 * Right-edge drawer that surfaces the production stats of the currently-selected farm.
 * Pure presentation: receives the farm + lookup tables as inputs and emits {@code closed}
 * when the player dismisses it.
 *
 * <p>Phase-6 placeholder: the rate is derived from the recipe's {@code operationDurationMinutes};
 * later phases will track per-building totals on the server and stream them here.
 */
@Component({
	selector: "app-farm-stats-drawer",
	imports: [DrawerModule, RecipeTileComponent, TranslocoDirective],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./farm-stats-drawer.component.html",
	styleUrl: "./farm-stats-drawer.component.scss",
})
export class FarmStatsDrawerComponent {
	private readonly languageService = inject(LanguageService);
	private readonly clockService = inject(ClockService);

	readonly farm = input<Building | null>(null);
	readonly recipes = input.required<readonly Recipe[]>();
	readonly ingredients = input.required<readonly Ingredient[]>();
	readonly operations = input<readonly Operation[]>([]);

	readonly closed = output<void>();

	protected readonly visible = computed(() => this.farm() !== null);

	protected readonly view = computed<FarmView | null>(() => {
		const farm = this.farm();
		if (!farm || farm.kind !== "FARM") {
			return null;
		}
		const language = this.languageService.activeLanguage();
		const recipe = this.recipes().find((candidate) => candidate.id === farm.recipeId);
		if (!recipe) {
			return null;
		}
		const ingredient = this.ingredients().find((candidate) => candidate.id === farm.outputIngredientId);
		const minutes = recipe.operationDurationMinutes;
		const quantityPerCycle = recipe.outputs[0]?.quantity ?? 1;
		const ratePerHour = (quantityPerCycle * 60) / Math.max(1, minutes);
		// Phase-17: lerp the produced-units counter forward between authoritative
		// building snapshots. Farms can't stall — every cycle that elapsed since the
		// anchor has finished — so a simple floor((live - anchor) / duration)
		// extrapolation matches what the server will report on the next refresh.
		const liveMinutes = this.clockService.liveGameMinutesSignal();
		const baseProduced = farm.producedUnits ?? 0;
		const cycleStart = farm.cycleStartedAtGameMinutes ?? -1;
		const cycleDuration = farm.cycleDurationGameMinutes ?? 0;
		let livedProduced = baseProduced;
		if (cycleStart >= 0 && cycleDuration > 0 && liveMinutes > cycleStart) {
			const extraCycles = Math.max(0, Math.floor((liveMinutes - cycleStart) / cycleDuration));
			// Match the server's `withProducedAdvance` semantics — one cycle bumps
			// the integer counter by exactly one regardless of the recipe's per-cycle
			// output quantity. Snapshot will reconcile on the next refresh.
			livedProduced = baseProduced + extraCycles;
		}
		return {
			recipe,
			outputIngredientName: ingredient
				? localize(ingredient.displayName, language)
				: (farm.outputIngredientId ?? "—"),
			ratePerHour: Math.round(ratePerHour * 10) / 10,
			producedUnits: livedProduced,
		};
	});

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.closed.emit();
		}
	}
}

