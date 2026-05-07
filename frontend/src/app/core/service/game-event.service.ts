import {DestroyRef, effect, inject, Injectable, signal} from "@angular/core";

import type {Building} from "../model/building.model";
import {ClockService} from "./clock.service";
import {GameService} from "./game.service";
import {ServerEventBusService} from "./server-event-bus.service";

/**
 * Phase-19: catch-all events stream consumer. Subscribes to
 * {@code /api/game/events/stream} and dispatches each event to the
 * appropriate domain service. Replaces the pre-Phase-19 model where the
 * frontend polled {@code /api/game/buildings} + {@code /api/game/balance}
 * after every vehicle arrival — the server now pushes every state change
 * with the full updated DTO baked into the event payload.
 *
 * <p>Each event reconciles by absolute {@code gameMinutes} + {@code
 * worldEpoch} rather than relative ordering — see {@code
 * docs/NETWORKING.md}. Events with a stale epoch are ignored (the
 * {@link ClockService} epoch transition triggers a cold-boot via
 * {@link GameService.bootstrapFromSnapshot}).
 */
@Injectable({providedIn: "root"})
export class GameEventService {
	private readonly clockService = inject(ClockService);
	private readonly gameService = inject(GameService);
	private readonly bus = inject(ServerEventBusService);
	private readonly destroyRef = inject(DestroyRef);

	/** Bumped on every received event; consumers can read this to react if
	 * they need to know "something changed". The detail is on the affected
	 * service's own signals. */
	private readonly _eventCount = signal<number>(0);
	readonly eventCount = this._eventCount.asReadonly();

	constructor() {
		const subscription = this.bus.game$.subscribe((event) => this.applyEvent(event));

		// Phase-19: epoch transition cold-boot. When the clock service notices
		// a `worldEpoch` mismatch (server reset, or we reconnected after one),
		// drop every cache and re-fetch the full snapshot.
		let initialEpochTick = true;
		effect(() => {
			this.clockService.epochCounter();
			if (initialEpochTick) {
				initialEpochTick = false;
				return;
			}
			this.gameService.bootstrapFromSnapshot().subscribe({error: () => undefined});
		});

		this.destroyRef.onDestroy(() => subscription.unsubscribe());
	}

	private applyEvent(event: GameEvent): void {
		// Stale-epoch guard: events older than our current world are ignored
		// because the cold-boot effect above will re-fetch the right state.
		if (event.worldEpoch < this.clockService.worldEpoch()) {
			return;
		}
		this._eventCount.update((n) => n + 1);
		switch (event.type) {
			case "BALANCE_CHANGED":
				this.gameService.applyBalance(event.newBalance);
				break;
			case "BUILDING_STATE_CHANGED":
				this.gameService.applyBuildingState(event.building);
				break;
			case "RESTAURANT_CLOSED":
				// Carried by the building DTO update; no separate action needed.
				// Leaving the case explicit so a future toast / SFX wires in here.
				break;
			case "WORLD_RESET":
				// Caches are dropped via the ClockService epoch transition above;
				// nothing else to do here. The reset response also pre-warms the
				// caches via bootstrapFromSnapshot.
				break;
		}
	}
}

interface BalanceChanged {
	readonly type: "BALANCE_CHANGED";
	readonly newBalance: number;
	readonly delta: number;
	readonly reason: string;
	readonly gameMinutes: number;
	readonly serverWallClockMs: number;
	readonly worldEpoch: number;
}

interface BuildingStateChanged {
	readonly type: "BUILDING_STATE_CHANGED";
	readonly building: Building;
	readonly gameMinutes: number;
	readonly serverWallClockMs: number;
	readonly worldEpoch: number;
}

interface RestaurantClosed {
	readonly type: "RESTAURANT_CLOSED";
	readonly restaurantId: string;
	readonly reputation: number;
	readonly gameMinutes: number;
	readonly serverWallClockMs: number;
	readonly worldEpoch: number;
}

interface WorldReset {
	readonly type: "WORLD_RESET";
	readonly gameMinutes: number;
	readonly serverWallClockMs: number;
	readonly worldEpoch: number;
	readonly reason: string | null;
}

export type GameEvent = BalanceChanged | BuildingStateChanged | RestaurantClosed | WorldReset;

