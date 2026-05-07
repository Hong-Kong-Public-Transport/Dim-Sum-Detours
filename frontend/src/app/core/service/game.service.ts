import {HttpClient, HttpErrorResponse} from "@angular/common/http";
import {inject, Injectable, signal} from "@angular/core";
import {catchError, map, Observable, of, tap, throwError} from "rxjs";

import {GAME_CONSTANTS} from "../constant/game.constants";
import type {
	BalanceResponse,
	Building,
	PlaceBuildingRequest,
	PlaceBuildingResponse,
} from "../model/building.model";

/**
 * Player wallet + placed-buildings state. Backed by {@code /api/game/*}; mirrors the
 * authoritative server-side {@code GameState}.
 */
@Injectable({providedIn: "root"})
export class GameService {
	private readonly httpClient = inject(HttpClient);

	private readonly _balance = signal<number>(GAME_CONSTANTS.economy.startingBalance);
	private readonly _buildings = signal<readonly Building[]>([]);
	/** Set true after the first {@link refreshBuildings} resolves so consumers (the restaurant
	 * spawner) can distinguish "no restaurants yet" from "haven't asked the server yet". */
	private readonly _buildingsLoaded = signal<boolean>(false);
	/** Monotonically incremented whenever the game is reset. Lets stateful services
	 * (e.g. {@code RestaurantSpawnerService}) re-arm one-shot guards via an effect. */
	private readonly _resetCount = signal<number>(0);

	readonly balance = this._balance.asReadonly();
	readonly buildings = this._buildings.asReadonly();
	readonly buildingsLoaded = this._buildingsLoaded.asReadonly();
	readonly resetCount = this._resetCount.asReadonly();

	refreshBalance(): Observable<BalanceResponse> {
		return this.httpClient.get<BalanceResponse>("/api/game/balance").pipe(
			tap((response) => this._balance.set(response.amount)),
		);
	}

	refreshBuildings(): Observable<readonly Building[]> {
		return this.httpClient.get<Building[]>("/api/game/buildings").pipe(
			tap((list) => {
				this._buildings.set(list);
				this._buildingsLoaded.set(true);
			}),
		);
	}

	/**
	 * POST a new building. On success, signals are updated locally so the caller doesn't need
	 * to refetch. On failure (4xx with a {@link PlaceBuildingResponse} body), the body's
	 * balance (if present) is synced and the error is re-thrown for the caller.
	 */
	placeBuilding(request: PlaceBuildingRequest): Observable<PlaceBuildingResponse> {
		return this.httpClient
			.post<PlaceBuildingResponse>("/api/game/buildings", request)
			.pipe(
				tap((response) => {
					if (response.building && response.balanceAmount !== null) {
						this._balance.set(response.balanceAmount);
						this._buildings.update((list) => [...list, response.building!]);
					}
				}),
				catchError((error: HttpErrorResponse) => {
					const body = error.error as PlaceBuildingResponse | null;
					if (body && typeof body.balanceAmount === "number") {
						this._balance.set(body.balanceAmount);
					}
					return throwError(() => error);
				}),
			);
	}

	demolishBuilding(id: string): Observable<void> {
		return this.httpClient.delete<void>(`/api/game/buildings/${encodeURIComponent(id)}`).pipe(
			tap(() => this._buildings.update((list) => list.filter((building) => building.id !== id))),
			map(() => void 0),
			catchError((error: HttpErrorResponse) => error.status === 404 ? of(void 0) : throwError(() => error)),
		);
	}

	/**
	 * Phase 5: replace the operation chain of a placed factory. Server validates that every id
	 * exists and that the multiset matches the recipe's required operations.
	 */
	updateFactoryOperations(id: string, operations: readonly string[]): Observable<Building> {
		return this.httpClient
			.put<Building>(
				`/api/game/buildings/${encodeURIComponent(id)}/operations`,
				{operations},
			)
			.pipe(
				tap((updated) => this._buildings.update((list) =>
					list.map((building) => building.id === updated.id ? updated : building),
				)),
			);
	}

	/**
	 * Phase-8 task 6: spend the refrigeration upgrade fee on a placed factory. The local
	 * buildings + balance signals are patched on success. The HTTP layer surfaces a 402
	 * PAYMENT_REQUIRED via the standard {@code HttpErrorResponse} when the wallet can't
	 * cover the cost; the caller decides whether to surface a toast.
	 */
	refrigerateFactory(buildingId: string): Observable<Building> {
		return this.httpClient
			.post<Building>(`/api/game/buildings/${encodeURIComponent(buildingId)}/refrigerate`, {})
			.pipe(
				tap((updated) => {
					this._buildings.update((list) =>
						list.map((building) => building.id === updated.id ? updated : building),
					);
					this.refreshBalance().subscribe({error: () => undefined});
				}),
			);
	}


	/**
	 * Wipes server-side state and resets local signals to the starting balance / empty list.
	 * Vehicle clearing is handled by {@link VehicleService}, which subscribes to
	 * {@link resetCount} via the consumer (the map component) — keeping reset choreography
	 * out of the wallet/buildings service.
	 *
	 * Phase-19: the backend now returns a full {@link GameSnapshotResponse}
	 * envelope instead of 204; we apply it locally so the player sees an
	 * empty world without waiting for any SSE round-trip. The
	 * {@code worldEpoch} bump in the response also wakes any subscriber
	 * keyed off {@link ClockService.epochCounter}.
	 */
	resetGame(): Observable<void> {
		return this.httpClient.post<GameSnapshotResponse>("/api/game/reset", {}).pipe(
			tap((snapshot) => {
				this._balance.set(snapshot.balance);
				this._buildings.set(snapshot.buildings);
				this._buildingsLoaded.set(true);
				this._resetCount.update((count) => count + 1);
			}),
			map(() => void 0),
		);
	}

	// ─── Phase-19: event-driven state mutators ────────────────────────────

	/** Replace the wallet balance (called from {@link GameEventService}
	 * applying {@code BALANCE_CHANGED}). The backend is authoritative. */
	applyBalance(newBalance: number): void {
		this._balance.set(newBalance);
	}

	/** Replace one building in the cache (called from
	 * {@link GameEventService} applying {@code BUILDING_STATE_CHANGED}).
	 * Insertion is O(n) per event, fine for the building counts we expect. */
	applyBuildingState(building: Building): void {
		this._buildings.update((list) => {
			const idx = list.findIndex((existing) => existing.id === building.id);
			if (idx < 0) {
				return [...list, building];
			}
			const next = list.slice();
			next[idx] = building;
			return next;
		});
		this._buildingsLoaded.set(true);
	}

	/**
	 * Phase-19: cold-boot. Fetches {@code /api/game/snapshot} and applies the
	 * full envelope (balance + buildings) to local signals in one shot. Used
	 * on app startup, on epoch-mismatch recovery, and after a long SSE
	 * disconnect. Vehicles + orders are owned by their respective services
	 * and refreshed via their own snapshot endpoints.
	 */
	bootstrapFromSnapshot(): Observable<GameSnapshotResponse> {
		return this.httpClient.get<GameSnapshotResponse>("/api/game/snapshot").pipe(
			tap((snapshot) => {
				this._balance.set(snapshot.balance);
				this._buildings.set(snapshot.buildings);
				this._buildingsLoaded.set(true);
			}),
		);
	}
}

/** Phase-19: wire shape of {@code GET /api/game/snapshot} and the new
 * {@code POST /api/game/reset} response. Other services may extend their
 * own bootstrap signatures off this — kept loose-typed for the unused
 * fields they don't care about. */
export interface GameSnapshotResponse {
	readonly clock: unknown;
	readonly balance: number;
	readonly buildings: readonly Building[];
	readonly vehicles: readonly unknown[];
	readonly orders: readonly unknown[];
	readonly milestones: readonly unknown[];
	readonly milestoneFulfilledCount: number;
	/** Phase-21 cold-boot: in-flight transit runs currently carrying
	 * cargo. The frontend's {@code CargoTransitService} hydrates its
	 * sprite-scale lookup from this list so a late-joining (or
	 * epoch-mismatched) client renders the scaled-up bus sprites for
	 * runs that started before its connection. May be {@code undefined}
	 * for older backends; consumers must null-guard. */
	readonly cargoTransitRuns?: readonly CargoTransitRunSnapshot[];
	/** Phase-21 step 4 cold-boot: cargo currently parked at a boarding
	 * stop waiting for the next ambient run on the planned route.
	 * Mirrors {@code com.dimsumdetours.api.GameController.WaitingCargoDto}.
	 * Empty until the boarding state machine starts populating
	 * {@code GameState.waitingCargo}. */
	readonly waitingCargo?: readonly WaitingCargoSnapshot[];
}

/** Phase-21 cold-boot wire shape: a single transit run + its cumulative
 * unit count. Mirrors {@code com.dimsumdetours.api.GameController.CargoTransitRunDto}. */
export interface CargoTransitRunSnapshot {
	readonly routeId: string;
	readonly departureOffsetGameMinutes: number;
	readonly totalCargoUnits: number;
}

/** Phase-21 step 4 cold-boot wire shape: cargo waiting at a boarding stop
 * for the next ambient run. Mirrors
 * {@code com.dimsumdetours.api.GameController.WaitingCargoDto}. */
export interface WaitingCargoSnapshot {
	readonly boardingStopId: string;
	readonly routeId: string;
	readonly totalCargoUnits: number;
}

