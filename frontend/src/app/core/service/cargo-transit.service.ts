import {DestroyRef, Injectable, inject, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";

import {transitRunKey, type CargoEvent, type TransitRunId} from "../model/cargo.model";
import {ServerEventBusService} from "./server-event-bus.service";

/**
 * Phase-21: per-run cargo accounting on the frontend. Subscribes to
 * the unified server event bus's {@code CARGO} channel and maintains
 * an in-memory map of {@code TransitRunId → totalCargoUnits} so the
 * ambient {@link TransitOverlayLayer} sprite can grow and shrink
 * with manifest count without the server needing to broadcast a
 * separate cargo-bus marker per run.
 *
 * <p>Backend emits {@code RUN_STARTED} / {@code CARGO_LOADED} /
 * {@code CARGO_UNLOADED} / {@code RUN_FINISHED} from
 * {@code GameState.advanceVehicles}'s boarding + alighting scans
 * (Phase-21 close-out). Cold-boot hydration via
 * {@link #hydrateFromSnapshot} catches up late-joining clients to
 * runs already in flight when their SSE subscription opened.
 *
 * <p>{@link MapComponent} wires {@link #cargoUnitsForRun} into
 * {@link TransitOverlayLayer.callbacks.cargoUnitsForRun}; the layer
 * polls it on every animation frame.
 */
@Injectable({providedIn: "root"})
export class CargoTransitService {
	private readonly bus = inject(ServerEventBusService);
	private readonly destroyRef = inject(DestroyRef);

	/** Run key (`routeId|departureOffset`) → total cargo units aboard.
	 * Exposed as a signal so consumers can derive computed signals
	 * (e.g. "any cargo on route X right now"). For O(1) hot-path
	 * lookups by ambient-bus animation, prefer {@link #cargoUnitsForRun}. */
	private readonly _runs = signal<ReadonlyMap<string, number>>(new Map());
	readonly runs = this._runs.asReadonly();

	constructor() {
		this.bus.cargo$
			.pipe(takeUntilDestroyed(this.destroyRef))
			.subscribe((event) => this.applyEvent(event));
	}

	/** O(1) lookup used by {@link TransitOverlayLayer}'s draw loop —
	 * no signal subscription, just a direct read off the live map. */
	cargoUnitsForRun(routeId: string, departureOffsetGameMinutes: number): number {
		return this._runs().get(this.keyFromParts(routeId, departureOffsetGameMinutes)) ?? 0;
	}

	private applyEvent(event: CargoEvent): void {
		const next = new Map(this._runs());
		const key = transitRunKey(event.run);
		switch (event.type) {
			case "RUN_STARTED":
				// First manifest already publishes a CARGO_LOADED with the
				// total — RUN_STARTED is purely a "this run now exists on
				// the server side" notification. Initialise to 0; the
				// CARGO_LOADED in the same tick will set the real total.
				if (!next.has(key)) {
					next.set(key, 0);
				}
				break;
			case "CARGO_LOADED":
			case "CARGO_UNLOADED":
				next.set(key, Math.max(0, event.totalCargoUnits));
				break;
			case "RUN_FINISHED":
				next.delete(key);
				break;
			default: {
				const _exhaustive: never = event;
				console.warn("cargo-transit unknown event variant", _exhaustive);
				return;
			}
		}
		this._runs.set(next);
	}

	/** Mirror of {@link transitRunKey} that takes split arguments —
	 * lets callers avoid allocating a {@link TransitRunId} object on
	 * every render-frame lookup. */
	private keyFromParts(routeId: string, departureOffsetGameMinutes: number): string {
		return `${routeId}|${departureOffsetGameMinutes}`;
	}

	/** Test / dev helper: force the run map to a specific state. Not
	 * called from production code. */
	overrideForTest(runs: ReadonlyMap<TransitRunId, number>): void {
		const next = new Map<string, number>();
		for (const [run, units] of runs) {
			next.set(transitRunKey(run), units);
		}
		this._runs.set(next);
	}

	/**
	 * Phase-21 cold-boot hydration. Replaces the live run map with the
	 * snapshot returned by {@code GET /api/game/snapshot.cargoTransitRuns}.
	 * Called from {@link AppComponent} after the snapshot fetch so a
	 * late-joining (or epoch-mismatched) client renders scaled-up bus
	 * sprites for runs that started before its SSE subscription opened.
	 *
	 * <p>Older backends may omit the field — passing {@code undefined}
	 * is treated as "clear local state" so a fresh world doesn't carry
	 * over stale entries from the previous epoch.
	 */
	hydrateFromSnapshot(
		runs: ReadonlyArray<{
			readonly routeId: string;
			readonly departureOffsetGameMinutes: number;
			readonly totalCargoUnits: number;
		}> | undefined
	): void {
		const next = new Map<string, number>();
		if (runs) {
			for (const run of runs) {
				next.set(
					this.keyFromParts(run.routeId, run.departureOffsetGameMinutes),
					Math.max(0, run.totalCargoUnits)
				);
			}
		}
		this._runs.set(next);
	}
}

