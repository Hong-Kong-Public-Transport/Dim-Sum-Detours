/**
 * Phase-12 vehicle model. Mirrors backend
 * {@code com.dimsumdetours.api.GameController.VehicleDto} +
 * {@code com.dimsumdetours.sim.model.vehicle.VehicleEvent}.
 */

export type VehicleKind = "ROBOT";

export interface Vehicle {
	readonly id: string;
	readonly kind: VehicleKind;
	readonly sourceBuildingId: string;
	readonly destinationBuildingId: string;
	readonly cargo: Readonly<Record<string, number>>;
	/** Each entry is {@code [lat, lon]}. Always at least 2 long. */
	readonly path: ReadonlyArray<readonly [number, number]>;
	readonly spawnedAtGameMinutes: number;
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
		 * Backend serialises the {@code Robot} record, whose {@code path} field is a
		 * {@code List<LatLon>} with {@code lat} + {@code lon} object members; the snapshot
		 * endpoint flattens the path into {@code double[2]} pairs. {@link VehicleService}
		 * normalises both shapes into {@link Vehicle}.
		 */
		readonly robot: VehicleWirePayload;
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
	readonly metersPerGameMinute?: number;
	readonly orderId: string | null;
	readonly spoilageDeadlineGameMinutes: number | null;
}

