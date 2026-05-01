import {HttpClient} from "@angular/common/http";
import {DestroyRef, effect, inject, Injectable, signal} from "@angular/core";

import {GAME_CONSTANTS} from "../constant/game.constants";
import type {Vehicle, VehicleEvent, VehicleWirePayload} from "../model/vehicle.model";
import {GameService} from "./game.service";

/**
 * Phase-12 vehicle renderer. Subscribes to {@code /api/game/vehicles/stream} for
 * {@code SPAWNED} / {@code ARRIVED} events, mirrors them into a {@link vehicles} signal,
 * and exposes a pure {@link interpolatePosition} helper the map component calls every
 * clock tick to position the robot marker.
 *
 * <p>The server is the source of truth — debit, advance, and credit all happen there.
 * This service has no dispatch logic of its own; "where every shipment is right now"
 * is computed locally only as a render-time interpolation off
 * {@code spawnedAt + path + speed}.
 */
@Injectable({providedIn: "root"})
export class VehicleService {
	private readonly httpClient = inject(HttpClient);
	private readonly gameService = inject(GameService);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _vehicles = signal<ReadonlyMap<string, Vehicle>>(new Map());
	readonly vehicles = this._vehicles.asReadonly();

	private eventSource: EventSource | null = null;

	constructor() {
		this.refreshSnapshot();
		this.openStream();
		// Drop every cached robot the moment GameService bumps the reset counter — the
		// backend will re-emit any post-reset SPAWNED events on the SSE stream, so we
		// can't fall behind by clearing eagerly.
		let initialReset = true;
		effect(() => {
			this.gameService.resetCount();
			if (initialReset) {
				initialReset = false;
				return;
			}
			this._vehicles.set(new Map());
		});
		this.destroyRef.onDestroy(() => this.eventSource?.close());
	}

	/**
	 * Pull the in-flight vehicles snapshot. Called on construction so a hot-reload (or a
	 * mid-flight tab refresh) immediately renders every robot at its current position.
	 */
	refreshSnapshot(): void {
		this.httpClient.get<readonly VehicleWirePayload[]>("/api/game/vehicles").subscribe({
			next: (list) => {
				const next = new Map<string, Vehicle>();
				for (const payload of list) {
					const vehicle = VehicleService.normalise(payload);
					next.set(vehicle.id, vehicle);
				}
				this._vehicles.set(next);
			},
			error: () => undefined,
		});
	}

	/**
	 * Walk the vehicle's path and return its current screen position. Linear interpolation
	 * over per-segment metres at the robot's constant {@link Vehicle#metersPerGameMinute}.
	 * Cheap — at most a handful of segments per vehicle today.
	 */
	interpolatePosition(vehicle: Vehicle, gameMinutes: number): {readonly lat: number; readonly lon: number} {
		const path = vehicle.path;
		if (path.length === 0) {
			return {lat: 0, lon: 0};
		}
		if (gameMinutes <= vehicle.spawnedAtGameMinutes || path.length === 1) {
			return {lat: path[0][0], lon: path[0][1]};
		}
		if (gameMinutes >= vehicle.arrivesAtGameMinutes) {
			const last = path[path.length - 1];
			return {lat: last[0], lon: last[1]};
		}
		// Distribute the spawn→arrive window proportionally to per-segment length.
		const segments: {readonly meters: number}[] = [];
		let totalMeters = 0;
		for (let i = 0; i < path.length - 1; i++) {
			const meters = haversineMetres(path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
			segments.push({meters});
			totalMeters += meters;
		}
		if (totalMeters <= 0) {
			return {lat: path[0][0], lon: path[0][1]};
		}
		const totalGameMinutes = vehicle.arrivesAtGameMinutes - vehicle.spawnedAtGameMinutes;
		let cursor = vehicle.spawnedAtGameMinutes;
		for (let i = 0; i < segments.length; i++) {
			const segmentGameMinutes = totalGameMinutes * (segments[i].meters / totalMeters);
			const segmentEnd = cursor + segmentGameMinutes;
			if (gameMinutes < segmentEnd) {
				const t = segmentGameMinutes <= 0 ? 0 : (gameMinutes - cursor) / segmentGameMinutes;
				const [aLat, aLon] = path[i];
				const [bLat, bLon] = path[i + 1];
				return {lat: aLat + (bLat - aLat) * t, lon: aLon + (bLon - aLon) * t};
			}
			cursor = segmentEnd;
		}
		const last = path[path.length - 1];
		return {lat: last[0], lon: last[1]};
	}

	/** Reset hook — clears the local cache so a fresh game starts with no ghost robots.
	 * Called by the {@link constructor} effect tracking {@link GameService.resetCount};
	 * exposed publicly for tests. */
	clear(): void {
		this._vehicles.set(new Map());
	}

	private openStream(): void {
		// Reuse the dev proxy: the EventSource hits /api/game/... on the same origin.
		this.eventSource = new EventSource("/api/game/vehicles/stream");
		this.eventSource.onmessage = (message) => {
			try {
				const event = JSON.parse(message.data) as VehicleEvent;
				if (typeof event.type !== "string") {
					// Phase-13 regression guard: if the wire payload is missing the `type`
					// discriminator (e.g. backend serialisation regression), log loudly so
					// it doesn't silently drop every spawn/arrive event the way the pre-fix
					// build did.
					console.error("vehicle stream frame missing 'type' discriminator", message.data);
					return;
				}
				this.applyEvent(event);
			} catch (err) {
				console.error("vehicle stream frame failed to parse", err, message.data);
			}
		};
		this.eventSource.onerror = (err) => {
			console.warn("vehicle stream error (browser will auto-reconnect)", err);
		};
	}

	private applyEvent(event: VehicleEvent): void {
		if (event.type === "SPAWNED") {
			const vehicle = VehicleService.normalise(event.robot);
			this._vehicles.update((map) => {
				const next = new Map(map);
				next.set(vehicle.id, vehicle);
				return next;
			});
		} else if (event.type === "ARRIVED") {
			this._vehicles.update((map) => {
				if (!map.has(event.vehicleId)) {
					return map;
				}
				const next = new Map(map);
				next.delete(event.vehicleId);
				return next;
			});
			// An arrival changes building state (factory stockpile or restaurant
			// reputation/balance). Refresh the local mirror so drawers update.
			this.gameService.refreshBuildings().subscribe({error: () => undefined});
			if (event.orderId !== null) {
				this.gameService.refreshBalance().subscribe({error: () => undefined});
			}
		}
	}

	/**
	 * Normalise a wire payload into the local {@link Vehicle} shape. Accepts both the
	 * snapshot form (path = {@code [lat, lon]} pairs) and the SSE record form
	 * (path = {@code {lat, lon}} objects).
	 */
	private static normalise(payload: VehicleWirePayload): Vehicle {
		const path: ReadonlyArray<readonly [number, number]> = payload.path.map((entry) => {
			if (Array.isArray(entry)) {
				return [entry[0] as number, entry[1] as number] as const;
			}
			const point = entry as {readonly lat: number; readonly lon: number};
			return [point.lat, point.lon] as const;
		});
		return {
			id: payload.id,
			kind: payload.kind ?? "ROBOT",
			sourceBuildingId: payload.sourceBuildingId,
			destinationBuildingId: payload.destinationBuildingId,
			cargo: payload.cargo,
			path,
			spawnedAtGameMinutes: payload.spawnedAtGameMinutes,
			arrivesAtGameMinutes: payload.arrivesAtGameMinutes,
			metersPerGameMinute: payload.metersPerGameMinute ?? GAME_CONSTANTS.robot.metersPerGameMinute,
			orderId: payload.orderId,
			spoilageDeadlineGameMinutes: payload.spoilageDeadlineGameMinutes,
		};
	}
}

/** Local copy of the placement-validator haversine — kept inline so the renderer
 * doesn't have to import a utility for one function. */
function haversineMetres(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const earthRadius = 6_371_000.0;
	const phi1 = (lat1 * Math.PI) / 180;
	const phi2 = (lat2 * Math.PI) / 180;
	const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
	const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;
	const a = Math.sin(deltaPhi / 2) ** 2
		+ Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) ** 2;
	return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

