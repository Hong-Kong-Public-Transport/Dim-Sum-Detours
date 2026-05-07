/**
 * Phase-21 frontend mirrors of the backend cargo-domain types from
 * {@code com.dimsumdetours.sim.model.vehicle.*}. Pure types — no
 * behaviour. Wire shapes only; the backend is the source of truth.
 *
 * <p>Used by {@link CargoTransitService} to track which transit runs
 * are currently carrying cargo so {@link TransitOverlayLayer} can
 * scale the ambient sprite by manifest count without needing a
 * separate cargo-bus marker.
 */

/** Canonical key for a transit run currently carrying cargo. The
 * frontend uses this to look up sprite-scale modifiers per run.
 *
 * <p>{@code departureOffsetGameMinutes} is the run's scheduled
 * departure as game-minutes since {@code t = 0}, computed from the
 * route run-time and headway slot. */
export interface TransitRunId {
	readonly routeId: string;
	readonly departureOffsetGameMinutes: number;
}

/** A shipment of cargo aboard (or about to board) a transit run. */
export interface CargoManifest {
	readonly id: string;
	readonly sourceBuildingId: string;
	readonly destinationBuildingId: string;
	readonly boardingStopId: string;
	readonly alightingStopId: string;
	readonly cargo: { readonly [ingredientId: string]: number };
	readonly orderId: string | null;
	readonly spoilageDeadlineGameMinutes: number | null;
	readonly postTransitPath: ReadonlyArray<{ readonly lat: number; readonly lon: number }>;
	readonly postTransitDurationGameMinutes: number;
	readonly loadedAtGameMinutes: number;
}

/** Cargo lifecycle event variants on the unified SSE channel.
 * Tagged-union mirror of {@code CargoEvent}'s sealed hierarchy. */
export type CargoEvent =
	| CargoEventRunStarted
	| CargoEventCargoLoaded
	| CargoEventCargoUnloaded
	| CargoEventRunFinished;

export interface CargoEventBase {
	readonly run: TransitRunId;
	readonly gameMinutes: number;
}

export interface CargoEventRunStarted extends CargoEventBase {
	readonly type: "RUN_STARTED";
	readonly gtfsRouteType: number;
}

export interface CargoEventCargoLoaded extends CargoEventBase {
	readonly type: "CARGO_LOADED";
	readonly manifestId: string;
	readonly totalCargoUnits: number;
}

export interface CargoEventCargoUnloaded extends CargoEventBase {
	readonly type: "CARGO_UNLOADED";
	readonly manifestId: string;
	readonly totalCargoUnits: number;
}

export interface CargoEventRunFinished extends CargoEventBase {
	readonly type: "RUN_FINISHED";
}

/** Build a stable string key from a {@link TransitRunId} for JS Map
 * usage (interfaces aren't structurally hashable). */
export function transitRunKey(run: TransitRunId): string {
	return `${run.routeId}|${run.departureOffsetGameMinutes}`;
}

