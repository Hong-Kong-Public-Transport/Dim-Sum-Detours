import {HttpClient} from "@angular/common/http";
import {inject, Injectable, signal} from "@angular/core";
import {catchError, of, tap} from "rxjs";

/**
 * Phase-18: client-side mirror of the backend's `/api/transit/snapshot` endpoint.
 * Loads once at construction time and caches the result in a signal — the
 * payload (stops + route shapes for the active feed) is immutable per backend
 * boot so there's no need for polling or SSE.
 *
 * <p>If the backend has no GTFS feed loaded the endpoint returns 503 and we
 * surface an empty snapshot — the map components LOD-gate the transit overlay
 * on `routes.length > 0` so an empty feed is silently invisible.
 */
@Injectable({providedIn: "root"})
export class TransitService {
	private readonly httpClient = inject(HttpClient);

	private readonly _snapshot = signal<TransitSnapshot>({stops: [], routes: []});
	readonly snapshot = this._snapshot.asReadonly();

	private readonly _loaded = signal<boolean>(false);
	readonly loaded = this._loaded.asReadonly();

	constructor() {
		this.refresh();
	}

	private refresh(): void {
		this.httpClient.get<TransitSnapshot>("/api/transit/snapshot").pipe(
			tap((snapshot) => {
				this._snapshot.set(snapshot);
				this._loaded.set(true);
			}),
			catchError(() => {
				// 503 (no feed) or any other error → keep the empty default; the
				// overlay will simply not render. Don't spam the console.
				this._loaded.set(true);
				return of(null);
			}),
		).subscribe();
	}
}

/** One transit stop. {@code routeIds} lists the routes that visit it (in their
 * representative-trip ordering — used for the hover popup). */
export interface TransitStop {
	readonly id: string;
	readonly name: string | null;
	readonly lat: number;
	readonly lon: number;
	readonly routeIds: readonly string[];
}

/** One transit route (per direction). {@code shape} is the polyline; {@code stopIds}
 * the ordered list of stops along the representative trip. */
export interface TransitRoute {
	readonly id: string;
	readonly routeId: string;
	readonly shortName: string | null;
	readonly longName: string | null;
	/** GTFS route_type: 0=tram, 1=metro, 2=rail, 3=bus, 4=ferry, … */
	readonly type: number;
	readonly colour: string | null;
	readonly textColour: string | null;
	readonly directionId: string | null;
	readonly stopIds: readonly string[];
	/** Each entry is [lat, lon]. */
	readonly shape: ReadonlyArray<readonly [number, number]>;
	/** Phase-19: per-stop cumulative game-minutes since the trip's first-stop
	 * departure. {@code -1} when the GTFS feed lacks scheduled times for the
	 * stop. Last finite entry = the route's run time in game-minutes. */
	readonly stopArrivalGameMinutes: readonly number[];
	readonly stopDepartureGameMinutes: readonly number[];
	/** Phase-19: index into {@link shape} where each stop sits. Frontend
	 * interpolates ambient bus position by stop_times rather than constant speed. */
	readonly stopShapeIndices: readonly number[];
}

export interface TransitSnapshot {
	readonly stops: readonly TransitStop[];
	readonly routes: readonly TransitRoute[];
}

