import {HttpClient} from "@angular/common/http";
import {inject, Injectable} from "@angular/core";
import {map, Observable} from "rxjs";

import type {Feature, FeatureCollection, PlacementZoneKind, Polygon} from "../model/geojson.model";

/** Raw Overpass element (way or relation with inline geometry from `out geom;`). */
interface OverpassElement {
	readonly type: "way" | "relation" | "node";
	readonly id: number;
	readonly tags?: Readonly<Record<string, string>>;
	readonly geometry?: ReadonlyArray<{lat: number; lon: number}>;
	readonly members?: ReadonlyArray<OverpassMember>;
}

interface OverpassMember {
	readonly type: string;
	readonly role: string;
	readonly geometry?: ReadonlyArray<{lat: number; lon: number}>;
}

interface OverpassResponse {
	readonly elements: ReadonlyArray<OverpassElement>;
}

/** A GeoJSON ring as `[lon, lat]` pairs. Phase-2 tolerance for "closed enough". */
const RING_TOLERANCE_DEGREES = 1e-7;

/**
 * Reads OSM placement zones from the backend and converts the raw Overpass JSON into
 * a GeoJSON {@link FeatureCollection} that Leaflet can consume directly.
 *
 * <p>Conversion rules (see {@link OsmService.toGeoJson}):
 * <ul>
 *   <li>A <strong>way</strong> whose first and last node coincide becomes a {@code Polygon}.</li>
 *   <li>An open way becomes a {@code LineString} (we never auto-close — auto-closing is what
 *       caused the "diagonal line cutting through the shape" rendering bug).</li>
 *   <li>A <strong>relation</strong>'s member ways are chained end-to-end into closed rings;
 *       only successfully-closed rings become polygons. Unclosed leftovers fall back to
 *       {@code LineString} so we still see <em>something</em> on the map.</li>
 * </ul>
 */
@Injectable({providedIn: "root"})
export class OsmService {
	private readonly httpClient = inject(HttpClient);

	placementZonesByFeed(feedName: string): Observable<FeatureCollection> {
		return this.httpClient
			.get<OverpassResponse>(
				`/api/osm/placement-zones/by-feed/${encodeURIComponent(feedName)}`,
			)
			.pipe(map((overpass) => OsmService.toGeoJson(overpass)));
	}

	placementZones(bbox: {
		south: number;
		west: number;
		north: number;
		east: number;
	}): Observable<FeatureCollection> {
		return this.httpClient
			.get<OverpassResponse>("/api/osm/placement-zones", {
				params: {
					south: String(bbox.south),
					west: String(bbox.west),
					north: String(bbox.north),
					east: String(bbox.east),
				},
			})
			.pipe(map((overpass) => OsmService.toGeoJson(overpass)));
	}

	/** Map an Overpass element's tags onto one of our placement-zone categories. */
	private static classify(tags: Readonly<Record<string, string>> | undefined): PlacementZoneKind | null {
		if (!tags) {
			return null;
		}
		if (tags["leisure"] === "park") return "park";
		if (tags["landuse"] === "farmland") return "farmland";
		if (tags["natural"] === "water") return "water";
		if (tags["natural"] === "coastline") return "coastline";
		if (tags["landuse"] === "commercial") return "commercial";
		return null;
	}

	private static ringFromGeometry(geometry: ReadonlyArray<{lat: number; lon: number}>): number[][] {
		// GeoJSON rings are [lon, lat]. We do NOT auto-close: callers decide whether the way
		// actually closes (first ≈ last) and degrade to LineString if it doesn't.
		return geometry.map((p) => [p.lon, p.lat]);
	}

	private static pointsEqual(a: number[], b: number[]): boolean {
		return Math.abs(a[0] - b[0]) <= RING_TOLERANCE_DEGREES
			&& Math.abs(a[1] - b[1]) <= RING_TOLERANCE_DEGREES;
	}

	private static isClosedRing(ring: number[][]): boolean {
		return ring.length >= 4 && OsmService.pointsEqual(ring[0], ring[ring.length - 1]);
	}

	/**
	 * Greedily chain way segments end-to-end into rings. Each segment is a list of `[lon,lat]`
	 * points; we attach the next segment whose head or tail matches the current ring's tail
	 * (reversing it if needed). When no further segment connects, the ring is emitted (closed
	 * if its head meets its tail).
	 */
	private static assembleRings(segments: number[][][]): {closed: number[][][]; open: number[][][]} {
		const remaining = segments.map((s) => s.slice());
		const closed: number[][][] = [];
		const open: number[][][] = [];

		while (remaining.length > 0) {
			const ring = remaining.shift()!;
			let extended = true;
			while (extended) {
				extended = false;
				const tail = ring[ring.length - 1];
				for (let i = 0; i < remaining.length; i++) {
					const seg = remaining[i];
					if (OsmService.pointsEqual(tail, seg[0])) {
						// Append (skip duplicate join point).
						for (let j = 1; j < seg.length; j++) ring.push(seg[j]);
						remaining.splice(i, 1);
						extended = true;
						break;
					}
					if (OsmService.pointsEqual(tail, seg[seg.length - 1])) {
						// Reverse-append.
						for (let j = seg.length - 2; j >= 0; j--) ring.push(seg[j]);
						remaining.splice(i, 1);
						extended = true;
						break;
					}
				}
			}
			if (OsmService.isClosedRing(ring)) {
				closed.push(ring);
			} else if (ring.length >= 2) {
				open.push(ring);
			}
		}
		return {closed, open};
	}

	private static toGeoJson(overpass: OverpassResponse): FeatureCollection {
		const features: Feature[] = [];
		for (const element of overpass.elements ?? []) {
			const kind = OsmService.classify(element.tags);
			if (!kind) {
				continue;
			}

			if (element.type === "way" && element.geometry && element.geometry.length >= 2) {
				const ring = OsmService.ringFromGeometry(element.geometry);
				const closed = OsmService.isClosedRing(ring);
				let geometry: Polygon;
				if (closed && kind !== "coastline") {
					geometry = {type: "Polygon", coordinates: [ring]};
				} else {
					// Open way (or coastline): render as a stroked line — never auto-close,
					// otherwise we get the "diagonal slash through the shape" bug.
					geometry = {type: "LineString", coordinates: ring};
				}
				features.push({
					type: "Feature",
					properties: {kind, osmId: `way/${element.id}`},
					geometry,
				});
			} else if (element.type === "relation" && element.members) {
				const outerSegments: number[][][] = [];
				const innerSegments: number[][][] = [];
				for (const member of element.members) {
					if (member.type !== "way" || !member.geometry || member.geometry.length < 2) {
						continue;
					}
					const seg = OsmService.ringFromGeometry(member.geometry);
					if (member.role === "inner") {
						innerSegments.push(seg);
					} else {
						outerSegments.push(seg);
					}
				}
				const {closed: outerRings, open: outerOpen} = OsmService.assembleRings(outerSegments);
				const {closed: innerRings} = OsmService.assembleRings(innerSegments);

				if (outerRings.length > 0) {
					// One polygon per outer ring. Inners are appended to the first outer that
					// would plausibly contain them — Phase-2 heuristic: just attach them all to
					// the first outer (Leaflet's geoJSON renderer handles holes correctly).
					const polygons: number[][][][] = outerRings.map((outer, idx) => {
						if (idx === 0 && innerRings.length > 0) {
							return [outer, ...innerRings];
						}
						return [outer];
					});
					features.push({
						type: "Feature",
						properties: {kind, osmId: `relation/${element.id}`},
						geometry: {type: "MultiPolygon", coordinates: polygons},
					});
				}

				// Anything that didn't close becomes a stroked line so the user still sees it.
				if (kind === "coastline" || outerOpen.length > 0) {
					for (const open of outerOpen) {
						features.push({
							type: "Feature",
							properties: {kind, osmId: `relation/${element.id}/open`},
							geometry: {type: "LineString", coordinates: open},
						});
					}
				}
			}
		}
		return {type: "FeatureCollection", features};
	}
}

