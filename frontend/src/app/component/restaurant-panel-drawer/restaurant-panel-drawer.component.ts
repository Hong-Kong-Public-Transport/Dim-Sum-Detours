import {ChangeDetectionStrategy, Component, computed, inject, input, output} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {DrawerModule} from "primeng/drawer";
import {ProgressBarModule} from "primeng/progressbar";

import {Building} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {RestaurantTemplate} from "../../core/model/restaurant-template.model";
import {ClockService} from "../../core/service/clock.service";
import {Order, RestaurantService} from "../../core/service/restaurant.service";
import {VehicleService} from "../../core/service/vehicle.service";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";

interface OrderRow {
	readonly order: Order;
	/** 0–100 — fraction of the patience window still on the clock. */
	readonly remainingPercent: number;
	readonly minutesRemaining: number;
	readonly severity: "success" | "warn" | "danger";
	/** Phase-12: order is enqueued but the server-side dispatcher hasn't found a robot
	 * to send yet (no producer with stock + matching recipe). The drawer surfaces an
	 * "awaiting supply" hint so the player knows the chain is incomplete rather than
	 * the game being broken. Re-derived per-render from {@link VehicleService.vehicles}. */
	readonly awaitingSupply: boolean;
}

/**
 * Right-edge drawer that surfaces the currently-selected restaurant's house dish, reputation,
 * and pending orders (each as a {@code p-progressbar} of the patience window). Pure
 * presentation — Phase-9 polish removed the manual test-order / fulfill buttons; orders
 * are now exclusively procedural and arrival is exclusively driven by the walker animation
 * landing at the restaurant marker.
 */
@Component({
	selector: "app-restaurant-panel-drawer",
	imports: [
		DrawerModule,
		ProgressBarModule,
		RecipeTileComponent,
		TranslocoDirective,
	],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./restaurant-panel-drawer.component.html",
	styleUrl: "./restaurant-panel-drawer.component.scss",
})
export class RestaurantPanelDrawerComponent {
	private readonly restaurantService = inject(RestaurantService);
	private readonly clockService = inject(ClockService);
	private readonly vehicleService = inject(VehicleService);

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

	/** Phase-13: lifetime fulfilled-order count surfaced under the reputation bar. */
	protected readonly fulfilledOrders = computed<number>(() => {
		const value = this.restaurant()?.fulfilledOrders;
		return value === undefined || value === null ? 0 : value;
	});

	/** Pending orders held against this restaurant, projected for the progress bars. */
	protected readonly orderRows = computed<readonly OrderRow[]>(() => {
		const restaurant = this.restaurant();
		if (!restaurant) {
			return [];
		}
		const allOrders = this.restaurantService.orders();
		// Phase-17: read the lerped game-minute so order patience bars + remaining-minute
		// labels advance smoothly between authoritative SSE snapshots instead of
		// stepping by whole-second jumps.
		const now = this.clockService.liveGameMinutesSignal();
		// Phase-12: an order is "awaiting supply" iff no in-flight robot is carrying it.
		// The server's VehicleDispatcher retries each tick, so the moment a producer
		// completes a cycle a robot will spawn and this flag will flip on the next render.
		const inFlightOrderIds = new Set<string>();
		for (const vehicle of this.vehicleService.vehicles().values()) {
			if (vehicle.orderId !== null) {
				inFlightOrderIds.add(vehicle.orderId);
			}
		}
		return allOrders
			.filter((order) => order.restaurantId === restaurant.id)
			.map((order) => RestaurantPanelDrawerComponent.toRow(order, now, !inFlightOrderIds.has(order.id)));
	});

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.closed.emit();
		}
	}

	private static toRow(order: Order, now: number, awaitingSupply: boolean): OrderRow {
		const total = Math.max(1, order.deadlineGameMinutes - order.createdAtGameMinutes);
		const remaining = Math.max(0, order.deadlineGameMinutes - now);
		const percent = Math.max(0, Math.min(100, Math.round((remaining / total) * 100)));
		const severity: OrderRow["severity"] = percent > 50 ? "success" : percent > 20 ? "warn" : "danger";
		return {order, remainingPercent: percent, minutesRemaining: Math.round(remaining), severity, awaitingSupply};
	}
}

