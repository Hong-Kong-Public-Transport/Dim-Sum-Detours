import {HttpClient} from "@angular/common/http";
import {DestroyRef, effect, inject, Injectable, signal} from "@angular/core";

import {GAME_CONSTANTS} from "../constant/game.constants";
import type {Vehicle, VehicleEvent, VehicleWirePayload} from "../model/vehicle.model";
import {GameService} from "./game.service";
import {ServerEventBusService} from "./server-event-bus.service";

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
	private readonly bus = inject(ServerEventBusService);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _vehicles = signal<ReadonlyMap<string, Vehicle>>(new Map());
	readonly vehicles = this._vehicles.asReadonly();

	constructor() {
		this.refreshSnapshot();
		// Phase-19 Phase-G: subscribe to the unified server-event bus.
		const subscription = this.bus.vehicle$.subscribe((event) => this.applyEvent(event));
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
			this.refreshSnapshot();
		});
		this.destroyRef.onDestroy(() => subscription.unsubscribe());
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
		// Phase-17: stationary at the source while loading.
		if (gameMinutes <= vehicle.departsAtGameMinutes || path.length === 1) {
			return {lat: path[0][0], lon: path[0][1]};
		}
		if (gameMinutes >= vehicle.arrivesAtGameMinutes) {
			const last = path[path.length - 1];
			return {lat: last[0], lon: last[1]};
		}
		// Distribute the depart→arrive window proportionally to per-segment length.
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
		const totalGameMinutes = vehicle.arrivesAtGameMinutes - vehicle.departsAtGameMinutes;
		let cursor = vehicle.departsAtGameMinutes;
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


	private applyEvent(event: VehicleEvent): void {
		if (event.type === "SPAWNED") {
			const vehicle = VehicleService.normalise(event.vehicle);
			this._vehicles.update((map) => {
				const next = new Map(map);
				next.set(vehicle.id, vehicle);
				return next;
			});
		} else if (event.type === "ARRIVED" || event.type === "ROBOT_ARRIVED_AT_STOP") {
			// Phase-21: ARRIVED credits the destination building (handled
			// server-side); ROBOT_ARRIVED_AT_STOP is the first-mile robot
			// despawning the moment it reaches the boarding stop (cargo flips
			// into the server-side WaitingCargo queue, no destination credit).
			// Both reduce to the same client-side action: drop the sprite.
			this._vehicles.update((map) => {
				if (!map.has(event.vehicleId)) {
					return map;
				}
				const next = new Map(map);
				next.delete(event.vehicleId);
				return next;
			});
			// Phase-19: cargo arrival mutations (factory stockpile, restaurant
			// reputation, balance) are now pushed by the server on
			// {@code /api/game/events/stream} as {@code BUILDING_STATE_CHANGED}
			// + {@code BALANCE_CHANGED} in the same tick. We no longer poll
			// {@code /api/game/buildings} or {@code /api/game/balance} here.
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
			sourceBuildingId: payload.sourceBuildingId,
			destinationBuildingId: payload.destinationBuildingId,
			cargo: payload.cargo,
			path,
			spawnedAtGameMinutes: payload.spawnedAtGameMinutes,
			departsAtGameMinutes: payload.departsAtGameMinutes ?? payload.spawnedAtGameMinutes,
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

