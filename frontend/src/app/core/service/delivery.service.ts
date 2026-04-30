import {DestroyRef, effect, inject, Injectable, signal} from "@angular/core";

import {GAME_CONSTANTS} from "../constant/game.constants";
import type {Building} from "../model/building.model";
import type {Recipe} from "../model/recipe.model";
import {ClockService} from "./clock.service";
import {ContentService} from "./content.service";
import {GameService} from "./game.service";
import {distanceMeters} from "../utility/placement-validator";
import type {Order, OrderEvent} from "./restaurant.service";
import {RestaurantService} from "./restaurant.service";

/** A live (in-flight) delivery animation. Consumed by the map component. */
export interface DeliveryAnimation {
	readonly id: string;
	readonly orderId: string;
	readonly restaurantId: string;
	readonly fromLat: number;
	readonly fromLon: number;
	readonly toLat: number;
	readonly toLon: number;
	/** Game-minute the delivery left its source. */
	readonly startedAtGameMinutes: number;
	/** Game-minute the delivery is expected to arrive. */
	readonly arrivesAtGameMinutes: number;
}

/**
 * Phase 6 delivery flow. Watches the order SSE stream via {@link RestaurantService#lastEvent}.
 * On {@code ENQUEUED}, picks the nearest farm/factory whose {@code recipeId} matches the order
 * and pushes an in-flight {@link DeliveryAnimation} onto the {@link animations} signal. An
 * internal effect, ticking off {@link ClockService#snapshot}, fulfils the order on the
 * backend once the game-minute deadline arrives.
 *
 * <p>Phase 6 ships a straight-line animation between source and restaurant; routing along an
 * actual GTFS trip shape is queued for Phase 7 (see README "Week 7+").
 */
@Injectable({providedIn: "root"})
export class DeliveryService {
	private readonly restaurantService = inject(RestaurantService);
	private readonly gameService = inject(GameService);
	private readonly clockService = inject(ClockService);
	private readonly contentService = inject(ContentService);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _animations = signal<readonly DeliveryAnimation[]>([]);
	readonly animations = this._animations.asReadonly();

	/** Cached recipe catalogue, kept in sync with {@link ContentService#listRecipes}. */
	private recipes: readonly Recipe[] = [];

	/** Order ids we've already dispatched, so duplicate ENQUEUED frames don't double-up. */
	private readonly dispatched = new Set<string>();
	/** Order ids we've already fulfilled, so the arrival effect only fires once. */
	private readonly fulfilled = new Set<string>();

	constructor() {
		this.contentService.listRecipes().subscribe((list) => {
			this.recipes = list;
		});

		effect(() => {
			const event = this.restaurantService.lastEvent();
			if (event && event.type === "ENQUEUED") {
				this.dispatch(event.order, event.gameMinutes);
			} else if (event && (event.type === "FULFILLED" || event.type === "EXPIRED")) {
				this.removeAnimation(event.orderId);
			}
		});

		effect(() => {
			const now = this.clockService.snapshot().gameMinutes;
			const arrived: DeliveryAnimation[] = [];
			for (const animation of this._animations()) {
				if (now >= animation.arrivesAtGameMinutes && !this.fulfilled.has(animation.orderId)) {
					arrived.push(animation);
				}
			}
			for (const animation of arrived) {
				this.fulfilled.add(animation.orderId);
				this.restaurantService.fulfillOrder(animation.restaurantId, animation.orderId).subscribe({
					next: () => {
						this.gameService.refreshBalance().subscribe({error: () => undefined});
						this.gameService.refreshBuildings().subscribe({error: () => undefined});
					},
					error: () => undefined,
				});
			}
		});

		this.destroyRef.onDestroy(() => {
			this.dispatched.clear();
			this.fulfilled.clear();
		});
	}

	/**
	 * Choose a source building (nearest farm/factory whose recipe matches) and queue an
	 * animation. No-op if no source exists — the order will simply expire on the backend.
	 */
	dispatch(order: Order, gameMinutes: number): void {
		if (this.dispatched.has(order.id)) {
			return;
		}
		const buildings = this.gameService.buildings();
		const restaurant = buildings.find((building) => building.id === order.restaurantId);
		if (!restaurant) {
			return;
		}
		const source = DeliveryService.pickNearestSource(buildings, order, restaurant, this.recipes);
		if (!source) {
			return;
		}

		const distance = distanceMeters(source.lat, source.lon, restaurant.lat, restaurant.lon);
		const travelGameMinutes = Math.max(1, Math.round(distance / GAME_CONSTANTS.delivery.metersPerGameMinute));
		const animation: DeliveryAnimation = {
			id: `delivery-${order.id}`,
			orderId: order.id,
			restaurantId: order.restaurantId,
			fromLat: source.lat,
			fromLon: source.lon,
			toLat: restaurant.lat,
			toLon: restaurant.lon,
			startedAtGameMinutes: gameMinutes,
			arrivesAtGameMinutes: gameMinutes + travelGameMinutes,
		};
		this.dispatched.add(order.id);
		this._animations.update((list) => [...list, animation]);
	}

	private removeAnimation(orderId: string): void {
		this._animations.update((list) => list.filter((animation) => animation.orderId !== orderId));
	}

	private static pickNearestSource(
		buildings: readonly Building[],
		order: Order,
		restaurant: Building,
		recipes: readonly Recipe[],
	): Building | null {
		// Preferred: a building running the exact requested recipe (top of the chain).
		const exact = DeliveryService.nearestMatching(
			buildings,
			restaurant,
			(candidate) => candidate.recipeId === order.recipeId,
		);
		if (exact) {
			return exact;
		}
		// Fallback: spawn a van from any upstream supplier whose recipe output is consumed
		// (transitively) by the order's recipe. This is the "ingredients have legs" intent
		// from the README — until full transit-aware walker animation lands in Phase 8, we
		// at least visualise that the player's farms ARE feeding the chain. Otherwise the
		// player builds a rice farm for a cha-siu-bao restaurant and sees nothing move.
		const consumedIngredients = DeliveryService.transitiveInputClosure(order.recipeId, recipes);
		if (consumedIngredients.size === 0) {
			return null;
		}
		const recipesById = new Map<string, Recipe>(recipes.map((recipe) => [recipe.id, recipe]));
		return DeliveryService.nearestMatching(buildings, restaurant, (candidate) => {
			const recipe = recipesById.get(candidate.recipeId);
			if (!recipe) {
				return false;
			}
			return recipe.outputs.some((output) => consumedIngredients.has(output.ingredientId));
		});
	}

	private static nearestMatching(
		buildings: readonly Building[],
		restaurant: Building,
		predicate: (candidate: Building) => boolean,
	): Building | null {
		let best: Building | null = null;
		let bestDistance = Number.POSITIVE_INFINITY;
		for (const candidate of buildings) {
			if (candidate.kind === "RESTAURANT" || !predicate(candidate)) {
				continue;
			}
			const distance = distanceMeters(candidate.lat, candidate.lon, restaurant.lat, restaurant.lon);
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Compute the set of ingredient ids consumed (directly or transitively) by the given
	 * recipe. Walks every input ingredient and recurses through any recipe that produces it.
	 * Cycles are guarded by a visited-recipe set.
	 */
	private static transitiveInputClosure(
		rootRecipeId: string,
		recipes: readonly Recipe[],
	): ReadonlySet<string> {
		const recipesById = new Map<string, Recipe>(recipes.map((recipe) => [recipe.id, recipe]));
		const recipesByOutput = new Map<string, Recipe[]>();
		for (const recipe of recipes) {
			for (const output of recipe.outputs) {
				const list = recipesByOutput.get(output.ingredientId) ?? [];
				list.push(recipe);
				recipesByOutput.set(output.ingredientId, list);
			}
		}
		const ingredients = new Set<string>();
		const visitedRecipes = new Set<string>();
		const queue: string[] = [rootRecipeId];
		while (queue.length > 0) {
			const recipeId = queue.shift()!;
			if (visitedRecipes.has(recipeId)) {
				continue;
			}
			visitedRecipes.add(recipeId);
			const recipe = recipesById.get(recipeId);
			if (!recipe) {
				continue;
			}
			for (const input of recipe.inputs) {
				if (ingredients.has(input.ingredientId)) {
					continue;
				}
				ingredients.add(input.ingredientId);
				const producers = recipesByOutput.get(input.ingredientId) ?? [];
				for (const producer of producers) {
					queue.push(producer.id);
				}
			}
		}
		return ingredients;
	}
}

// Type re-exports kept so callers don't have to import from two places.
export type {Order, OrderEvent};

