import {effect, inject, Injectable} from "@angular/core";

import {GAME_CONSTANTS} from "../constant/game.constants";
import type {Building} from "../model/building.model";
import type {Feature, FeatureCollection, PlacementZoneKind} from "../model/geojson.model";
import type {RestaurantTemplate} from "../model/restaurant-template.model";
import {GameService} from "./game.service";

/** Lat/lon centroid of an OSM polygon outer ring. */
interface SpawnCandidate {
	readonly latitude: number;
	readonly longitude: number;
	readonly kind: PlacementZoneKind;
}

/**
 * Phase 6 auto-spawner. Picks a small set of residential/commercial centroids from the loaded
 * OSM placement zones and posts each as a {@code RESTAURANT} via {@link GameService#placeBuilding}.
 *
 * <p>Frontend-driven because the parsed {@link FeatureCollection} only lives on the frontend —
 * the backend hands back raw Overpass JSON. Best-effort: errors (insufficient funds, density
 * cap, invalid placement) are swallowed silently because over-spawning is preferable to a
 * blank map.
 */
@Injectable({providedIn: "root"})
export class RestaurantSpawnerService {
	private readonly gameService = inject(GameService);

	/** Set true after the first successful spawn pass so language toggles don't re-spawn. */
	private alreadySpawned = false;
	/** Counter snapshot — when the GameService reset count advances, re-arm the guard. */
	private lastObservedResetCount = 0;

	constructor() {
		// Re-arm whenever the player clicks reset. The map's spawn effect will re-fire as
		// `buildings()` flips back to empty, and we'll spawn a fresh roster of restaurants.
		effect(() => {
			const count = this.gameService.resetCount();
			if (count !== this.lastObservedResetCount) {
				this.lastObservedResetCount = count;
				this.alreadySpawned = false;
			}
		});
	}

	/**
	 * Drop {@link GAME_CONSTANTS.spawn.restaurantsPerWorld} restaurants onto the map. No-op
	 * if already invoked, if no eligible zones / templates are present, if the buildings
	 * list hasn't been refreshed from the server yet, or if the server already reports a
	 * restaurant (prevents duplicate spawns across page reloads against a still-warm
	 * backend).
	 */
	spawnFromZones(
		zones: FeatureCollection,
		templates: readonly RestaurantTemplate[],
		existing: readonly Building[],
	): void {
		if (this.alreadySpawned || templates.length === 0) {
			return;
		}
		// Avoid the race where Overpass + content arrive before the buildings refresh — we'd
		// otherwise see an empty buildings list, decide to spawn, and quickly stack a second
		// roster on top of whatever the backend already had.
		if (!this.gameService.buildingsLoaded()) {
			return;
		}
		if (existing.some((building) => building.kind === "RESTAURANT")) {
			this.alreadySpawned = true;
			return;
		}
		const candidates = RestaurantSpawnerService.collectCandidates(zones);
		if (candidates.length === 0) {
			return;
		}

		this.alreadySpawned = true;
		const target = Math.min(GAME_CONSTANTS.spawn.restaurantsPerWorld, candidates.length);
		const shuffled = RestaurantSpawnerService.shuffleDeterministic(candidates);
		for (let index = 0; index < target; index++) {
			const candidate = shuffled[index];
			const template = templates[index % templates.length];
			const recipeId = template.acceptedRecipeIds[0];
			if (!recipeId) {
				continue;
			}
			this.gameService.placeBuilding({
				kind: "RESTAURANT",
				lat: candidate.latitude,
				lon: candidate.longitude,
				recipeId,
				templateId: template.id,
			}).subscribe({
				error: () => undefined,
			});
		}
	}

	/** For tests: re-arm the one-shot guard so a fresh spawn pass can run. */
	reset(): void {
		this.alreadySpawned = false;
	}

	private static collectCandidates(zones: FeatureCollection): SpawnCandidate[] {
		const eligible = new Set<PlacementZoneKind>(GAME_CONSTANTS.spawn.restaurantZoneKinds);
		const out: SpawnCandidate[] = [];
		for (const feature of zones.features) {
			if (!eligible.has(feature.properties.kind)) {
				continue;
			}
			const centroid = RestaurantSpawnerService.featureCentroid(feature);
			if (centroid) {
				out.push({...centroid, kind: feature.properties.kind});
			}
		}
		return out;
	}

	private static featureCentroid(feature: Feature): {latitude: number; longitude: number} | null {
		const geometry = feature.geometry;
		if (geometry.type === "Polygon") {
			return RestaurantSpawnerService.ringCentroid(geometry.coordinates[0]);
		}
		if (geometry.type === "MultiPolygon" && geometry.coordinates.length > 0) {
			return RestaurantSpawnerService.ringCentroid(geometry.coordinates[0][0]);
		}
		return null;
	}

	private static ringCentroid(ring: ReadonlyArray<ReadonlyArray<number>>): {latitude: number; longitude: number} | null {
		if (ring.length === 0) {
			return null;
		}
		let totalLatitude = 0;
		let totalLongitude = 0;
		for (const point of ring) {
			totalLongitude += point[0];
			totalLatitude += point[1];
		}
		return {latitude: totalLatitude / ring.length, longitude: totalLongitude / ring.length};
	}

	/**
	 * Stable shuffle via a small Linear Congruential Generator seeded off the candidate count —
	 * keeps the spawn pattern deterministic across page reloads (same map + same zones → same
	 * restaurants), which is far less jarring than fresh-random every time.
	 */
	private static shuffleDeterministic<T>(items: readonly T[]): T[] {
		const out = items.slice();
		let seed = (items.length * 2654435761) >>> 0;
		for (let i = out.length - 1; i > 0; i--) {
			seed = (seed * 1664525 + 1013904223) >>> 0;
			const j = seed % (i + 1);
			[out[i], out[j]] = [out[j], out[i]];
		}
		return out;
	}
}

