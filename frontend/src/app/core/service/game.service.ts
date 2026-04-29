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

	readonly balance = this._balance.asReadonly();
	readonly buildings = this._buildings.asReadonly();

	refreshBalance(): Observable<BalanceResponse> {
		return this.httpClient.get<BalanceResponse>("/api/game/balance").pipe(
			tap((response) => this._balance.set(response.amount)),
		);
	}

	refreshBuildings(): Observable<readonly Building[]> {
		return this.httpClient.get<Building[]>("/api/game/buildings").pipe(
			tap((list) => this._buildings.set(list)),
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
	 * Wipes server-side state and resets local signals to the starting balance / empty list.
	 */
	resetGame(): Observable<void> {
		return this.httpClient.post<void>("/api/game/reset", {}).pipe(
			tap(() => {
				this._balance.set(GAME_CONSTANTS.economy.startingBalance);
				this._buildings.set([]);
			}),
			map(() => void 0),
		);
	}
}

