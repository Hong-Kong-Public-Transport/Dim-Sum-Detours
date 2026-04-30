import {ChangeDetectionStrategy, Component, computed, inject, input, output, signal} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {DrawerModule} from "primeng/drawer";
import {ProgressBarModule} from "primeng/progressbar";
import {TooltipModule} from "primeng/tooltip";

import {Building} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {RestaurantTemplate} from "../../core/model/restaurant-template.model";
import {ClockService} from "../../core/service/clock.service";
import {GameService} from "../../core/service/game.service";
import {Order, RestaurantService} from "../../core/service/restaurant.service";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";

interface OrderRow {
	readonly order: Order;
	/** 0–100 — fraction of the patience window still on the clock. */
	readonly remainingPercent: number;
	readonly minutesRemaining: number;
	readonly severity: "success" | "warn" | "danger";
}

/**
 * Right-edge drawer that surfaces the currently-selected restaurant's house dish, reputation,
 * and pending orders (each as a {@code p-progressbar} of the patience window). Pure
 * presentation aside from the two action buttons (enqueue test order / fulfill), which
 * delegate to {@link RestaurantService}.
 */
@Component({
	selector: "app-restaurant-panel-drawer",
	imports: [
		ButtonModule,
		DrawerModule,
		ProgressBarModule,
		RecipeTileComponent,
		TooltipModule,
		TranslocoDirective,
	],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./restaurant-panel-drawer.component.html",
	styleUrl: "./restaurant-panel-drawer.component.scss",
})
export class RestaurantPanelDrawerComponent {
	private readonly restaurantService = inject(RestaurantService);
	private readonly clockService = inject(ClockService);
	private readonly gameService = inject(GameService);

	readonly restaurant = input<Building | null>(null);
	readonly recipes = input.required<readonly Recipe[]>();
	readonly ingredients = input.required<readonly Ingredient[]>();
	readonly operations = input<readonly Operation[]>([]);
	readonly templates = input<readonly RestaurantTemplate[]>([]);

	readonly closed = output<void>();

	protected readonly visible = computed(() => this.restaurant() !== null);

	protected readonly recipe = computed<Recipe | null>(() => {
		const restaurant = this.restaurant();
		if (!restaurant) {
			return null;
		}
		return this.recipes().find((candidate) => candidate.id === restaurant.recipeId) ?? null;
	});

	protected readonly template = computed<RestaurantTemplate | null>(() => {
		const restaurant = this.restaurant();
		if (!restaurant?.templateId) {
			return null;
		}
		return this.templates().find((candidate) => candidate.id === restaurant.templateId) ?? null;
	});

	/** Reputation as a 0–100 percentage for the progress bar. */
	protected readonly reputationPercent = computed(() => {
		const value = this.restaurant()?.reputation;
		return value === undefined || value === null ? 0 : Math.round(value * 100);
	});

	/** Pending orders held against this restaurant, projected for the progress bars. */
	protected readonly orderRows = computed<readonly OrderRow[]>(() => {
		const restaurant = this.restaurant();
		if (!restaurant) {
			return [];
		}
		const allOrders = this.restaurantService.orders();
		const now = this.clockService.snapshot().gameMinutes;
		return allOrders
			.filter((order) => order.restaurantId === restaurant.id)
			.map((order) => RestaurantPanelDrawerComponent.toRow(order, now));
	});

	/** Local "submitting" flag so the enqueue button can disable while a request is in flight. */
	protected readonly submitting = signal(false);

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.closed.emit();
		}
	}

	protected enqueueTestOrder(): void {
		const restaurant = this.restaurant();
		if (!restaurant) {
			return;
		}
		const template = this.template();
		const patience = template?.basePatienceMinutes ?? 240;
		this.submitting.set(true);
		this.restaurantService.enqueueOrder(restaurant.id, {
			recipeId: restaurant.recipeId,
			quantity: 1,
			patienceGameMinutes: patience,
		}).subscribe({
			next: () => this.submitting.set(false),
			error: () => this.submitting.set(false),
		});
	}

	protected fulfill(orderId: string): void {
		const restaurant = this.restaurant();
		if (!restaurant) {
			return;
		}
		this.restaurantService.fulfillOrder(restaurant.id, orderId).subscribe({
			next: (response) => {
				// Server credited the wallet; pull the authoritative numbers + buildings so
				// the marker re-renders with the bumped reputation.
				this.gameService.refreshBalance().subscribe({error: () => undefined});
				this.gameService.refreshBuildings().subscribe({error: () => undefined});
				void response;
			},
			error: () => undefined,
		});
	}

	private static toRow(order: Order, now: number): OrderRow {
		const total = Math.max(1, order.deadlineGameMinutes - order.createdAtGameMinutes);
		const remaining = Math.max(0, order.deadlineGameMinutes - now);
		const percent = Math.max(0, Math.min(100, Math.round((remaining / total) * 100)));
		const severity: OrderRow["severity"] = percent > 50 ? "success" : percent > 20 ? "warn" : "danger";
		return {order, remainingPercent: percent, minutesRemaining: remaining, severity};
	}
}

