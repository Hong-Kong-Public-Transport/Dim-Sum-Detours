/**
 * Phase-12 vehicle model. Mirrors backend
 * {@code com.dimsumdetours.api.GameController.VehicleDto} +
 * {@code com.dimsumdetours.sim.model.vehicle.VehicleEvent}.
 *
 * <p>Phase-21: the backend stopped emitting {@code Bus} payloads — every
 * {@link Vehicle} on the wire is a robot, and transit cargo flows through
 * the dedicated {@code CARGO_LOADED} / {@code CARGO_UNLOADED} channel
 * (see {@code cargo.model.ts}). The {@code kind} / {@code tripId} /
 * {@code routeId} bus-attribution fields are gone; this file no longer
 * carries them.
 */

export interface Vehicle {
	readonly id: string;
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
}

/** Server-emitted vehicle lifecycle events streamed off {@code /api/game/vehicles/stream}. */
export type VehicleEvent =
	| {
		readonly type: "SPAWNED";
		readonly gameMinutes: number;
		/**
		 * Backend serialises a {@link Vehicle} sealed type (Phase-21:
		 * {@code Robot}-only after the {@code Bus} deletion). The
		 * {@link VehicleService.normalise} helper accepts the raw payload
		 * and produces a {@link Vehicle}.
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
	}
	| {
		/**
		 * Phase-21: a first-mile robot reached its boarding stop and was
		 * despawned server-side (its cargo flipped into the
		 * {@code WaitingCargo} queue). Frontend treats this exactly like
		 * a removal — drop the sprite, no destination credit. Distinct
		 * from {@code ARRIVED} so the per-event handler doesn't have to
		 * infer "robot vanished mid-route" from a missing destination.
		 */
		readonly type: "ROBOT_ARRIVED_AT_STOP";
		readonly gameMinutes: number;
		readonly vehicleId: string;
		readonly boardingStopId: string;
		readonly routeId: string;
	};

/**
 * Loose wire payload — the SSE {@code Spawned} event sends the raw {@code Robot} record
 * (path = {@code List<LatLon>}) while the snapshot endpoint sends the flattened
 * {@code double[2]} pairs. {@link VehicleService.normalise} accepts either.
 */
export interface VehicleWirePayload {
	readonly id: string;
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
}
