/**
 * Minimal GeoJSON typings for the placement-zone feature collection. We don't need full
 * RFC 7946 coverage — just polygons / multi-polygons / line-strings tagged with a `kind`.
 */

export type PlacementZoneKind =
	| "park"
	| "farmland"
	| "water"
	| "coastline"
	| "commercial"
	| "residential";

export interface PlacementZoneProperties {
	readonly kind: PlacementZoneKind;
	readonly osmId: string;
	/** Human-readable name copied from OSM `tags.name`, when present. */
	readonly name?: string;
}

export type Polygon =
	| {readonly type: "Polygon"; readonly coordinates: number[][][]}
	| {readonly type: "MultiPolygon"; readonly coordinates: number[][][][]}
	| {readonly type: "LineString"; readonly coordinates: number[][]};

export interface Feature {
	readonly type: "Feature";
	readonly properties: PlacementZoneProperties;
	readonly geometry: Polygon;
}

export interface FeatureCollection {
	readonly type: "FeatureCollection";
	readonly features: readonly Feature[];
}
