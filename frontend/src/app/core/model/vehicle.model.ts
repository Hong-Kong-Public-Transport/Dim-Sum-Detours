/**
 * Phase-12 vehicle model. Mirrors backend
 * {@code com.dimsumdetours.api.GameController.VehicleDto} +
 * {@code com.dimsumdetours.sim.model.vehicle.VehicleEvent}.
 */

export type VehicleKind = "ROBOT" | "BUS";

export interface Vehicle {
	readonly id: string;
	readonly kind: VehicleKind;
	readonly sourceBuildingId: string;
	readonly destinationBuildingId: string;
	readonly cargo: Readonly<Record<string, number>>;
	/** Each entry is {@code [lat, lon]}. Always at least 2 long. */
	readonly path: ReadonlyArray<readonly [number, number]>;
	readonly spawnedAtGameMinutes: number;
	/**
	 * Phase-17: game-minute the vehicle finishes loading and starts moving.
	 * The frontend renders the marker stationary at {@code path[0]} until this
	 * minute, then interpolates along the path through to {@link arrivesAtGameMinutes}.
	 * Encoding the loading window on the spawn frame means a single SSE event
	 * is enough to animate the whole lifecycle, even when the next frame
	 * doesn't arrive for hundreds of ms (high game-speed coalescing).
	 */
	readonly departsAtGameMinutes: number;
	readonly arrivesAtGameMinutes: number;
	readonly metersPerGameMinute: number;
	readonly orderId: string | null;
	readonly spoilageDeadlineGameMinutes: number | null;
	/** Phase-16: GTFS trip id when {@link kind} is {@code "BUS"}; null for robots. */
	readonly tripId: string | null;
	/** Phase-16: GTFS route id when {@link kind} is {@code "BUS"}; null for robots. */
	readonly routeId: string | null;
}

/** Server-emitted vehicle lifecycle events streamed off {@code /api/game/vehicles/stream}. */
export type VehicleEvent =
	| {
		readonly type: "SPAWNED";
		readonly gameMinutes: number;
		/**
		 * Backend serialises a {@link Vehicle} sealed type (Phase-16: was {@code Robot}-only,
		 * now {@code Robot} or {@code Bus} for multi-leg GTFS chains). The polymorphic JSON
		 * carries a {@code "kind"} discriminator. {@link VehicleService.normalise}
		 * accepts the raw payload and produces a {@link Vehicle}.
		 */
		readonly vehicle: VehicleWirePayload;
	}
	| {
		readonly type: "ARRIVED";
		readonly gameMinutes: number;
		readonly vehicleId: string;
		readonly destinationBuildingId: string;
		readonly orderId: string | null;
		readonly orderResult: "FULFILLED" | "LATE" | "SPOILED" | null;
	};

/**
 * Loose wire payload — the SSE {@code Spawned} event sends the raw {@code Robot} record
 * (path = {@code List<LatLon>}) while the snapshot endpoint sends the flattened
 * {@code double[2]} pairs. {@link VehicleService.normalise} accepts either.
 */
export interface VehicleWirePayload {
	readonly id: string;
	readonly kind?: VehicleKind;
	readonly sourceBuildingId: string;
	readonly destinationBuildingId: string;
	readonly cargo: Readonly<Record<string, number>>;
	readonly path: ReadonlyArray<readonly [number, number] | {readonly lat: number; readonly lon: number}>;
	readonly spawnedAtGameMinutes: number;
	readonly arrivesAtGameMinutes: number;
	/** Phase-17. Optional for back-compat; defaults to {@code spawnedAtGameMinutes}. */
	readonly departsAtGameMinutes?: number;
	readonly metersPerGameMinute?: number;
	readonly orderId: string | null;
	readonly spoilageDeadlineGameMinutes: number | null;
	readonly tripId?: string | null;
	readonly routeId?: string | null;
}

