import type {Feature, FeatureCollection, PlacementZoneKind} from "../model/geojson.model";
import type {BuildingKind} from "../model/building.model";

/**
 * Allowed placement zones per building kind. Mirrors the README's spatial-puzzle rules:
 * <ul>
 *   <li><b>FARM</b> — only inside parks (community gardens) or farmland.</li>
 *   <li><b>FACTORY</b> — only inside commercial zones.</li>
 * </ul>
 *
 * Restaurants are spawned automatically in residential/commercial mix later (Phase 6),
 * so they're not in this map yet.
 */
export const ALLOWED_ZONES: Readonly<Record<BuildingKind, ReadonlyArray<PlacementZoneKind>>> = Object.freeze({
	FARM: Object.freeze(["park", "farmland"] as const),
	FACTORY: Object.freeze(["commercial"] as const),
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
	kind: BuildingKind,
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

