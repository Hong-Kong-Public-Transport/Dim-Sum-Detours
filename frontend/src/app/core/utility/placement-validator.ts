import type {Feature, FeatureCollection, PlacementZoneKind} from "../model/geojson.model";
import type {Building, BuildingKind} from "../model/building.model";

/** Subset of {@link BuildingKind} that the player places by hand. */
type PlaceableBuildingKind = Extract<BuildingKind, "FARM" | "FACTORY" | "RESTAURANT">;

/**
 * Allowed placement zones per building kind. Mirrors the README's spatial-puzzle rules:
 * <ul>
 *   <li><b>FARM</b> — only inside parks (community gardens) or farmland.</li>
 *   <li><b>FACTORY</b> — only inside commercial zones.</li>
 *   <li><b>RESTAURANT</b> — residential or commercial mix (Phase 6: manual placement; later
 *       phases auto-spawn).</li>
 * </ul>
 */
export const ALLOWED_ZONES: Readonly<Record<PlaceableBuildingKind, ReadonlyArray<PlacementZoneKind>>> = Object.freeze({
	FARM: Object.freeze(["park", "farmland"] as const),
	FACTORY: Object.freeze(["commercial"] as const),
	RESTAURANT: Object.freeze(["residential", "commercial"] as const),
});

/** Standard ray-casting point-in-polygon. Ring is `[lon, lat][]`. */
function isPointInRing(longitude: number, latitude: number, ring: ReadonlyArray<ReadonlyArray<number>>): boolean {
	let inside = false;
	for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
		const [longitudeI, latitudeI] = ring[i];
		const [longitudeJ, latitudeJ] = ring[j];
		const intersects = ((latitudeI > latitude) !== (latitudeJ > latitude))
			&& (longitude < ((longitudeJ - longitudeI) * (latitude - latitudeI)) / (latitudeJ - latitudeI) + longitudeI);
		if (intersects) {
			inside = !inside;
		}
	}
	return inside;
}

function isPointInPolygon(longitude: number, latitude: number, polygon: ReadonlyArray<ReadonlyArray<ReadonlyArray<number>>>): boolean {
	if (polygon.length === 0) {
		return false;
	}
	if (!isPointInRing(longitude, latitude, polygon[0])) {
		return false;
	}
	// Holes (inner rings): if the point sits inside a hole, it's not in the polygon.
	for (let i = 1; i < polygon.length; i++) {
		if (isPointInRing(longitude, latitude, polygon[i])) {
			return false;
		}
	}
	return true;
}

function featureContains(feature: Feature, longitude: number, latitude: number): boolean {
	const geometry = feature.geometry;
	if (geometry.type === "Polygon") {
		return isPointInPolygon(longitude, latitude, geometry.coordinates);
	}
	if (geometry.type === "MultiPolygon") {
		for (const polygon of geometry.coordinates) {
			if (isPointInPolygon(longitude, latitude, polygon)) {
				return true;
			}
		}
	}
	// LineString (open coastlines / unclosed relations) has no interior.
	return false;
}

/**
 * True iff the given lat/lon falls inside at least one feature whose {@code kind} is allowed
 * for the requested {@code BuildingKind}. Used both for the cursor-validity preview and the
 * confirm-button gate.
 */
export function isValidPlacement(
	kind: PlaceableBuildingKind,
	latitude: number,
	longitude: number,
	collection: FeatureCollection | null,
): boolean {
	if (!collection) {
		// No zones loaded yet — fall back to permissive so the player isn't blocked while
		// the Overpass response is in flight.
		return true;
	}
	const allowed = ALLOWED_ZONES[kind];
	for (const feature of collection.features) {
		if (!allowed.includes(feature.properties.kind)) {
			continue;
		}
		if (featureContains(feature, longitude, latitude)) {
			return true;
		}
	}
	return false;
}

/** Great-circle distance in metres (WGS-84 mean radius). Mirrors backend `GameState.haversineMetres`. */
export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const earthRadiusMeters = 6_371_000;
	const phi1 = (lat1 * Math.PI) / 180;
	const phi2 = (lat2 * Math.PI) / 180;
	const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
	const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;
	const a = Math.sin(deltaPhi / 2) ** 2
		+ Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) ** 2;
	const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	return earthRadiusMeters * c;
}

/**
 * True iff no existing same-kind building sits within {@code minSpacingMeters} of the proposed
 * location. Mirrors the backend density cap so the cursor preview matches the server's verdict.
 */
export function respectsSpacing(
	kind: PlaceableBuildingKind,
	latitude: number,
	longitude: number,
	buildings: readonly Building[],
	minSpacingMeters: number,
): boolean {
	for (const existing of buildings) {
		if (existing.kind !== kind) {
			continue;
		}
		if (distanceMeters(existing.lat, existing.lon, latitude, longitude) < minSpacingMeters) {
			return false;
		}
	}
	return true;
}

