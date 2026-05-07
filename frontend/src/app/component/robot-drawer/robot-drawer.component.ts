import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {DrawerModule} from "primeng/drawer";

import {localize} from "../../core/i18n/localize";
import type {Building} from "../../core/model/building.model";
import type {Ingredient} from "../../core/model/ingredient.model";
import type {Vehicle} from "../../core/model/vehicle.model";
import {ClockService} from "../../core/service/clock.service";
import {LanguageService} from "../../core/service/language.service";

interface CargoLine {
	readonly ingredientId: string;
	readonly displayName: string;
	readonly quantity: number;
}

interface RobotView {
	readonly id: string;
	readonly cargo: readonly CargoLine[];
	readonly sourceName: string;
	readonly destinationName: string;
	readonly etaMinutes: number;
	readonly progressPercent: number;
	readonly status: "LOADING" | "TRAVEL";
}

/**
 * Right-edge drawer for an in-flight robot: shows what it's carrying, its source +
 * destination, and a live ETA. Pure presentation — receives the {@link Vehicle} +
 * lookup tables as inputs and emits {@code closed} when dismissed.
 *
 * <p>The list of vehicles is owned by {@code VehicleService}; once a robot arrives
 * (server-emitted ARRIVED event), it leaves that signal and the parent map clears the
 * selection, which closes this drawer automatically.
 */
@Component({
	selector: "app-robot-drawer",
	imports: [DrawerModule, TranslocoDirective],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./robot-drawer.component.html",
	styleUrl: "./robot-drawer.component.scss",
})
export class RobotDrawerComponent {
	private readonly languageService = inject(LanguageService);
	private readonly clockService = inject(ClockService);

	readonly robot = input<Vehicle | null>(null);
	readonly buildings = input.required<readonly Building[]>();
	readonly ingredients = input.required<readonly Ingredient[]>();

	readonly closed = output<void>();

	protected readonly visible = computed(() => this.robot() !== null);

	protected readonly view = computed<RobotView | null>(() => {
		const robot = this.robot();
		if (!robot) {
			return null;
		}
		const language = this.languageService.activeLanguage();
		const buildingsById = new Map(this.buildings().map((building) => [building.id, building]));
		const ingredientsById = new Map(this.ingredients().map((ingredient) => [ingredient.id, ingredient]));

		const cargo: CargoLine[] = [];
		for (const [ingredientId, quantity] of Object.entries(robot.cargo)) {
			const ingredient = ingredientsById.get(ingredientId);
			cargo.push({
				ingredientId,
				displayName: ingredient
					? localize(ingredient.displayName, language)
					: ingredientId,
				quantity,
			});
		}

		const source = buildingsById.get(robot.sourceBuildingId);
		const destination = buildingsById.get(robot.destinationBuildingId);

		const now = this.clockService.liveGameMinutesSignal();
		const etaMinutes = Math.max(0, Math.round(robot.arrivesAtGameMinutes - now));
		// Phase-17: progress is measured against the depart→arrive window, not the
		// spawn→arrive window — while loading, the bar stays at 0%.
		const status: "LOADING" | "TRAVEL" = now < robot.departsAtGameMinutes ? "LOADING" : "TRAVEL";
		const travelSpan = Math.max(1, robot.arrivesAtGameMinutes - robot.departsAtGameMinutes);
		const elapsed = Math.max(0, Math.min(travelSpan, now - robot.departsAtGameMinutes));
		const progressPercent = status === "LOADING" ? 0 : Math.round((elapsed / travelSpan) * 100);

		return {
			id: robot.id,
			cargo,
			sourceName: source ? RobotDrawerComponent.buildingLabel(source) : robot.sourceBuildingId,
			destinationName: destination ? RobotDrawerComponent.buildingLabel(destination) : robot.destinationBuildingId,
			etaMinutes,
			progressPercent,
			status,
		};
	});

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.closed.emit();
		}
	}

	private static buildingLabel(building: Building): string {
		// Cheap label: kind + last 6 of the id. Per-building names are a future content
		// pass; today the kind + recipe id is what the player has to navigate by.
		const tail = building.id.slice(-6);
		return `${building.kind.toLowerCase()} (${building.recipeId}) — ${tail}`;
	}
}

