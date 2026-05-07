/**
 * Phase-18: ambient transit overlay. Renders the loaded GTFS feed's stops as
 * Leaflet circle markers with a hover popup listing the downstream stops on
 * each route serving the stop, and animates one synthetic bus per route per
 * {@code BUS_HEADWAY_GAME_MINUTES} cadence sliding along the route's shape.
 *
 * <p>Decoupled from real GTFS schedules — the published timetable is ignored
 * intentionally so transit availability stays predictable for gameplay (a
 * bus is always at most 5 game-minutes away regardless of in-game time-of-day).
 *
 * <p>Both the stops layer and the ambient-bus Pixi sprites are LOD-gated by the
 * map's zoom level: at zoom &lt; {@code MIN_TRANSIT_RENDER_ZOOM} the entire
 * overlay hides, because at country-scale zoom a 1k-stop feed becomes visual
 * noise and a perf cliff for the WebGL frame-budget.
 */
import "leaflet-pixi-overlay";
import * as Leaflet from "leaflet";
import * as PIXI from "pixi.js";

import type {TransitRoute, TransitSnapshot, TransitStop} from "./transit.service";

interface PixiOverlayUtils {
	getContainer(): PIXI.Container;
	getRenderer(): PIXI.IRenderer;
	getMap(): Leaflet.Map;
	getScale(): number;
	latLngToLayerPoint(latlng: Leaflet.LatLngExpression): Leaflet.Point;
}

interface PixiOverlay extends Leaflet.Layer {
	redraw(data?: unknown): this;
}

interface PixiOverlayFactory {
	(
		drawCallback: (utils: PixiOverlayUtils) => void,
		container: PIXI.Container,
		options?: Record<string, unknown>,
	): PixiOverlay;
}

const pixiOverlayFactory = (Leaflet as unknown as {pixiOverlay: PixiOverlayFactory}).pixiOverlay;

/** Constant in-game cadence between successive ambient buses on the same route. */
const BUS_HEADWAY_GAME_MINUTES = 5;

export interface TransitOverlayCallbacks {
	/** Read each frame to drive ambient-bus animation. */
	currentGameMinutes(): number;
	/** Return the active transit snapshot (stops + routes). */
	snapshot(): TransitSnapshot;
	/** Phase-19: cargo riding bus run {@code k} of {@code routeId}. The
	 * {@code departureOffset} is the absolute game-minute that run departed
	 * the route's first stop. Returns 0 when no cargo is aboard.
	 *
	 * <p>Drives the "bus icon grows when carrying cargo, shrinks as it
	 * unloads" visual — replaces the previous separate cargo-bus Pixi sprite. */
	cargoUnitsForRun?(routeId: string, departureOffset: number): number;
}

interface RouteRuntime {
	readonly route: TransitRoute;
	/** Cumulative shape distance per vertex (metres), {@code distances[0] = 0}. */
	readonly distances: Float64Array;
	/** Total shape length in metres. */
	readonly totalMeters: number;
	/** End-to-end run time in game-minutes — from GTFS {@code stop_times},
	 * NOT a constant cruise speed. The last finite
	 * {@code stopArrivalGameMinutes} entry, falling back to a coarse
	 * shape-meters / 420 estimate only when the entire feed lacks times. */
	readonly runTimeGameMinutes: number;
	/** Per-stop cumulative game-min into the run (mirrors snapshot, with
	 * {@code -1}s replaced by linearly-interpolated values across gaps). */
	readonly stopArrivalGameMinutes: Float64Array;
	/** Per-stop cumulative shape-meters (looked up via {@code stopShapeIndices}). */
	readonly stopShapeMeters: Float64Array;
}

/**
 * Manages the transit overlay (stops layer + ambient bus Pixi sprites). Construct
 * with the Leaflet map, supply callbacks for game-minute + snapshot, and call
 * {@link start} to begin animation. {@link destroy} on unmount.
 */
export class TransitOverlayLayer {
	private readonly stopsLayer: Leaflet.LayerGroup;
	private readonly stopMarkers = new Map<string, Leaflet.CircleMarker>();
	private readonly pixiContainer: PIXI.Container;
	private readonly overlay: PixiOverlay;
	private readonly busSprites = new Map<string, PIXI.Graphics>();
	/** Phase-19: invisible Leaflet circle-marker proxies above each ambient bus
	 * sprite so we can attach hover tooltips. Pixi sprites can't bind Leaflet
	 * tooltips directly. Mirrors {@code RobotPixiLayer.clickMarkers}. */
	private readonly busTooltipMarkers = new Map<string, Leaflet.CircleMarker>();
	private readonly busTooltipLayer: Leaflet.LayerGroup;
	private routeRuntime: readonly RouteRuntime[] = [];
	private routesById = new Map<string, TransitRoute>();
	private stopsById = new Map<string, TransitStop>();
	private animationFrame: number | null = null;
	private destroyed = false;
	private lastZoomVisible = false;
	private readonly minRenderZoom: number;

	constructor(
		private readonly map: Leaflet.Map,
		private readonly callbacks: TransitOverlayCallbacks,
		minRenderZoom: number,
	) {
		this.minRenderZoom = minRenderZoom;
		if (typeof pixiOverlayFactory !== "function") {
			throw new Error("TransitOverlayLayer: leaflet-pixi-overlay plugin failed to attach");
		}
		this.stopsLayer = Leaflet.layerGroup();
		this.busTooltipLayer = Leaflet.layerGroup();
		this.pixiContainer = new PIXI.Container();
		this.pixiContainer.sortableChildren = true;

		const TRANSIT_PANE = "transitPane";
		if (!this.map.getPane(TRANSIT_PANE)) {
			const pane = this.map.createPane(TRANSIT_PANE);
			// Just below the robot pane (650) but above the default overlay pane (400),
			// so ambient buses paint behind cargo robots.
			pane.style.zIndex = "620";
			pane.style.pointerEvents = "none";
		}
		this.overlay = pixiOverlayFactory(
			(utils) => this.drawAmbientBuses(utils),
			this.pixiContainer,
			{autoPreventDefault: false, pane: TRANSIT_PANE},
		);

		// React to zoom changes for LOD gating.
		this.map.on("zoomend", () => this.applyZoomVisibility());
	}

	/** Attach the snapshot once it's available — the layer stays empty until then. */
	setSnapshot(snapshot: TransitSnapshot): void {
		this.stopsById = new Map(snapshot.stops.map((stop) => [stop.id, stop]));
		this.routesById = new Map(snapshot.routes.map((route) => [route.id, route]));
		this.routeRuntime = snapshot.routes
			.map((route) => TransitOverlayLayer.precomputeRoute(route))
			.filter((runtime): runtime is RouteRuntime => runtime !== null);
		this.rebuildStops(snapshot.stops);
		this.applyZoomVisibility();
	}

	start(): void {
		if (this.animationFrame !== null) {
			return;
		}
		const tick = (): void => {
			if (this.destroyed) {
				return;
			}
			this.overlay.redraw();
			this.animationFrame = requestAnimationFrame(tick);
		};
		this.animationFrame = requestAnimationFrame(tick);
	}

	destroy(): void {
		this.destroyed = true;
		if (this.animationFrame !== null) {
			cancelAnimationFrame(this.animationFrame);
			this.animationFrame = null;
		}
		this.map.removeLayer(this.overlay);
		this.map.removeLayer(this.stopsLayer);
		this.map.removeLayer(this.busTooltipLayer);
		for (const sprite of this.busSprites.values()) {
			sprite.destroy();
		}
		this.busSprites.clear();
		this.busTooltipMarkers.clear();
		this.pixiContainer.destroy({children: true});
		this.stopMarkers.clear();
	}

	/** Show or hide the entire overlay based on the current map zoom level. */
	private applyZoomVisibility(): void {
		const visible = this.map.getZoom() >= this.minRenderZoom && this.routeRuntime.length > 0;
		if (visible === this.lastZoomVisible) {
			return;
		}
		this.lastZoomVisible = visible;
		if (visible) {
			this.stopsLayer.addTo(this.map);
			this.busTooltipLayer.addTo(this.map);
			this.overlay.addTo(this.map);
		} else {
			this.map.removeLayer(this.stopsLayer);
			this.map.removeLayer(this.busTooltipLayer);
			this.map.removeLayer(this.overlay);
		}
	}

	private rebuildStops(stops: readonly TransitStop[]): void {
		this.stopsLayer.clearLayers();
		this.stopMarkers.clear();
		for (const stop of stops) {
			const marker = Leaflet.circleMarker([stop.lat, stop.lon], {
				radius: 4,
				color: "#1f6feb",
				weight: 1,
				fillColor: "#9ec5ff",
				fillOpacity: 0.85,
				interactive: true,
				bubblingMouseEvents: false,
			});
			marker.bindTooltip(() => this.renderStopTooltip(stop), {
				direction: "top",
				offset: [0, -4],
				opacity: 0.95,
				sticky: true,
			});
			marker.addTo(this.stopsLayer);
			this.stopMarkers.set(stop.id, marker);
		}
	}

	/** Hover-popup HTML for a stop: stop name + the routes serving that stop. */
	private renderStopTooltip(stop: TransitStop): string {
		const safeName = stop.name ?? stop.id;
		const lines: string[] = [
			`<strong>${escapeHtml(safeName)}</strong>`,
		];
		const routesForStop = stop.routeIds
			.flatMap((routeId) => Array.from(this.routesById.values()).filter((r) => r.routeId === routeId));
		// De-duplicate by routeId+direction id (each direction is its own entry).
		const seen = new Set<string>();
		const unique = routesForStop.filter((r) => {
			if (seen.has(r.id)) return false;
			seen.add(r.id);
			return true;
		});
		if (unique.length === 0) {
			lines.push(`<div style="opacity:0.7;">No routes</div>`);
		}
		for (const route of unique.slice(0, 12)) {
			lines.push(`<div style="margin-top: 2px; color:#1f6feb; font-weight:600;">`
				+ escapeHtml(formatRouteLabel(route))
				+ `</div>`);
		}
		return lines.join("");
	}

	/** Hover-popup HTML for an ambient bus: route label + final destination stop. */
	private renderBusTooltip(route: TransitRoute): string {
		const finalStopId = route.stopIds[route.stopIds.length - 1];
		const finalStop = finalStopId ? this.stopsById.get(finalStopId) : undefined;
		const destination = finalStop?.name ?? finalStopId ?? "—";
		return `<strong>${escapeHtml(formatRouteLabel(route))}</strong>`
			+ `<div style="opacity:0.85;">→ ${escapeHtml(destination)}</div>`;
	}

	/** Pixi redraw — paint one bus glyph per active "trip" along each route. */
	private drawAmbientBuses(utils: PixiOverlayUtils): void {
		if (!this.lastZoomVisible) {
			return;
		}
		const now = this.callbacks.currentGameMinutes();
		const scale = 1 / utils.getScale();
		const liveIds = new Set<string>();

		for (const runtime of this.routeRuntime) {
			if (runtime.runTimeGameMinutes <= 0) {
				continue;
			}
			// Each route runs N concurrent buses, where N = ceil(runTime / headway).
			// Bus k departs the first stop at gameMinute (k * headway), so its
			// progress along the run at `now` is ((now - k*headway) mod runTime).
			const concurrent = Math.max(1, Math.ceil(runtime.runTimeGameMinutes / BUS_HEADWAY_GAME_MINUTES));
			for (let k = 0; k < concurrent; k++) {
				// Reconstruct this run's absolute departure offset (= the most
				// recent k*headway boundary ≤ now whose phase mod-cycle matches k).
				// Equivalent: depOffset = floor((now − k*H) / runTime) * runTime + k*H.
				const phaseRaw = now - k * BUS_HEADWAY_GAME_MINUTES;
				const cycles = Math.floor(phaseRaw / runtime.runTimeGameMinutes);
				const departureOffset = cycles * runtime.runTimeGameMinutes + k * BUS_HEADWAY_GAME_MINUTES;
				const phase = phaseRaw - cycles * runtime.runTimeGameMinutes;

				// GTFS-time interpolation: locate the stop pair (i, i+1) such
				// that stopArrival[i] ≤ phase ≤ stopArrival[i+1], then map
				// linearly onto shape-meters between those stops.
				const distance = TransitOverlayLayer.distanceForPhase(runtime, phase);
				const position = TransitOverlayLayer.interpolateAlong(runtime, distance);
				if (!position) {
					continue;
				}
				const id = runtime.route.id + "#" + k;
				liveIds.add(id);
				let sprite = this.busSprites.get(id);
				if (!sprite) {
					sprite = TransitOverlayLayer.makeBusGlyph(runtime.route);
					this.busSprites.set(id, sprite);
					this.pixiContainer.addChild(sprite);
				}
				const point = utils.latLngToLayerPoint([position[0], position[1]]);
				sprite.position.set(point.x, point.y);
				// Phase-19: cargo scaling — sprite grows when this run carries
				// cargo, shrinks back when cargo unloads. Replaces the previous
				// separate cargo-bus Pixi sprite (RobotPixiLayer skips BUS now).
				const units = this.callbacks.cargoUnitsForRun?.(runtime.route.routeId, departureOffset) ?? 0;
				const cargoScale = 1 + Math.min(0.5, units * 0.05);
				sprite.scale.set(scale * cargoScale, scale * cargoScale);
				sprite.alpha = units > 0 ? 0.95 : 0.7; // slightly brighten when carrying cargo

				// Phase-19 tooltips: invisible Leaflet circle-marker tracks the
				// Pixi sprite so a hover over the painted bus pops a Leaflet
				// tooltip with route label + final destination.
				let tooltipMarker = this.busTooltipMarkers.get(id);
				if (!tooltipMarker) {
					tooltipMarker = Leaflet.circleMarker([position[0], position[1]], {
						radius: 9,
						stroke: false,
						fillOpacity: 0,
						interactive: true,
						bubblingMouseEvents: false,
					});
					tooltipMarker.bindTooltip(() => this.renderBusTooltip(runtime.route), {
						direction: "top",
						offset: [0, -4],
						opacity: 0.95,
						sticky: true,
					});
					tooltipMarker.addTo(this.busTooltipLayer);
					this.busTooltipMarkers.set(id, tooltipMarker);
				} else {
					tooltipMarker.setLatLng([position[0], position[1]]);
				}
			}
		}

		// Drop any sprites whose route was dropped (e.g. snapshot reload).
		for (const [id, sprite] of this.busSprites) {
			if (liveIds.has(id)) {
				continue;
			}
			this.pixiContainer.removeChild(sprite);
			sprite.destroy();
			this.busSprites.delete(id);
			const tooltipMarker = this.busTooltipMarkers.get(id);
			if (tooltipMarker) {
				this.busTooltipLayer.removeLayer(tooltipMarker);
				this.busTooltipMarkers.delete(id);
			}
		}

		// Without this explicit render call the WebGL surface never repaints —
		// pixi-overlay invokes our draw callback on redraw() but expects the
		// callback to push a frame to the renderer itself (mirrors RobotPixiLayer).
		utils.getRenderer().render(this.pixiContainer);
	}

	/** Pre-compute cumulative shape distances + per-stop shape/time anchors for one route. */
	private static precomputeRoute(route: TransitRoute): RouteRuntime | null {
		if (route.shape.length < 2) {
			return null;
		}
		const distances = new Float64Array(route.shape.length);
		let total = 0;
		distances[0] = 0;
		for (let i = 1; i < route.shape.length; i++) {
			const [aLat, aLon] = route.shape[i - 1];
			const [bLat, bLon] = route.shape[i];
			total += haversineMetres(aLat, aLon, bLat, bLon);
			distances[i] = total;
		}
		if (total <= 0) {
			return null;
		}

		// Per-stop cumulative shape-meters (lookup via stopShapeIndices).
		const stopCount = route.stopIds.length;
		const stopShapeMeters = new Float64Array(stopCount);
		for (let i = 0; i < stopCount; i++) {
			const idx = Math.max(0, Math.min(distances.length - 1, route.stopShapeIndices[i] ?? 0));
			stopShapeMeters[i] = distances[idx];
		}

		// Per-stop cumulative game-minutes from GTFS. Phase-20 onward, the
		// backend's {@code TransitSnapshotService.interpolateMissingStopTimes}
		// already fills every {@code -1} sentinel by shape-distance linear
		// interpolation (anchored on whatever finite cells the feed *did*
		// schedule, falling back to {@code BUS_METERS_PER_GAME_MINUTE} when
		// no anchor exists). The wire is therefore guaranteed NaN-free.
		//
		// Defence-in-depth: clamp any residual {@code < 0} cell to 0 so a
		// regressed backend can't produce a non-monotonic spline that drives
		// the binary-search later in this file off-by-one. We no longer fan
		// out into a per-stop NaN-patching loop — that pre-Phase-20
		// frontend-side interpolation was redundant the moment the backend
		// owned the gap-filling, and was a frequent source of silent
		// disagreement when the two interpolators ran on different
		// assumptions (e.g. trailing-anchor extrapolation vs.
		// distance-fallback).
		const stopArrivalGameMinutes = new Float64Array(stopCount);
		const raw = route.stopArrivalGameMinutes;
		for (let i = 0; i < stopCount; i++) {
			stopArrivalGameMinutes[i] = Math.max(0, raw[i]);
		}

		const runTimeGameMinutes = stopArrivalGameMinutes[stopCount - 1] || 1;
		return {
			route,
			distances,
			totalMeters: total,
			runTimeGameMinutes,
			stopArrivalGameMinutes,
			stopShapeMeters,
		};
	}

	/** Map a {@code phase} game-minute (∈ [0, runTime]) onto cumulative
	 * shape-meters using the per-stop GTFS arrival times. Linearly interpolates
	 * within the bracketing stop pair. */
	private static distanceForPhase(runtime: RouteRuntime, phase: number): number {
		const arr = runtime.stopArrivalGameMinutes;
		const meters = runtime.stopShapeMeters;
		if (arr.length === 0) return 0;
		if (phase <= arr[0]) return meters[0];
		if (phase >= arr[arr.length - 1]) return meters[meters.length - 1];
		// Binary search for the stop pair (i, i+1) bracketing `phase`.
		let lo = 0;
		let hi = arr.length - 1;
		while (lo < hi) {
			const mid = (lo + hi) >>> 1;
			if (arr[mid + 1] < phase) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		const i = Math.min(lo, arr.length - 2);
		const span = arr[i + 1] - arr[i];
		const t = span <= 0 ? 0 : (phase - arr[i]) / span;
		return meters[i] + (meters[i + 1] - meters[i]) * t;
	}

	/** Linear interpolate {@code distance} metres into the route's shape. */
	private static interpolateAlong(
		runtime: RouteRuntime,
		distance: number,
	): readonly [number, number] | null {
		const {route, distances} = runtime;
		if (route.shape.length === 0) {
			return null;
		}
		// Binary search for the segment containing `distance`.
		let lo = 0;
		let hi = distances.length - 1;
		while (lo < hi) {
			const mid = (lo + hi) >>> 1;
			if (distances[mid + 1] < distance) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		const i = Math.min(lo, distances.length - 2);
		const segLength = distances[i + 1] - distances[i];
		const t = segLength <= 0 ? 0 : (distance - distances[i]) / segLength;
		const [aLat, aLon] = route.shape[i];
		const [bLat, bLon] = route.shape[i + 1];
		return [aLat + (bLat - aLat) * t, aLon + (bLon - aLon) * t];
	}

	/** Phase-20: mode-agnostic glyph picker. The behaviour (animation,
	 * boarding, cargo scaling) is identical across every GTFS route_type;
	 * only the icon silhouette changes so the player can tell a tram from
	 * a ferry at a glance. Falls through to the bus rectangle for unknown
	 * types so a feed with exotic mode codes still renders something. */
	private static makeBusGlyph(route: TransitRoute): PIXI.Graphics {
		const g = new PIXI.Graphics();
		const fill = route.colour ? parseHexColour(route.colour) : 0xfacc15;
		switch (route.type) {
			case 0: case 12: // tram, monorail — slim rounded body
				g.beginFill(fill, 1);
				g.lineStyle(1, 0x111111, 0.7);
				g.drawRoundedRect(-8, -3, 16, 6, 2);
				g.endFill();
				g.beginFill(0xbfdbfe, 0.9);
				g.drawRect(-6, -2, 12, 1.5);
				g.endFill();
				return g;
			case 1: case 2: case 11: // metro, rail, trolleybus — longer carriage with multiple windows
				g.beginFill(fill, 1);
				g.lineStyle(1, 0x111111, 0.7);
				g.drawRoundedRect(-9, -4, 18, 8, 1);
				g.endFill();
				g.beginFill(0xbfdbfe, 0.9);
				g.drawRect(-7, -2.5, 3, 2);
				g.drawRect(-2, -2.5, 4, 2);
				g.drawRect(4, -2.5, 3, 2);
				g.endFill();
				return g;
			case 4: // ferry — boat hull
				g.beginFill(fill, 1);
				g.lineStyle(1, 0x111111, 0.7);
				g.moveTo(-7, -2);
				g.lineTo(7, -2);
				g.lineTo(5, 3);
				g.lineTo(-5, 3);
				g.lineTo(-7, -2);
				g.endFill();
				g.beginFill(0xbfdbfe, 0.9);
				g.drawRect(-3, -1, 6, 2);
				g.endFill();
				return g;
			case 5: case 6: case 7: // cable car / aerial lift / funicular — small cab
				g.beginFill(fill, 1);
				g.lineStyle(1, 0x111111, 0.7);
				g.drawRoundedRect(-4, -4, 8, 8, 1.5);
				g.endFill();
				g.beginFill(0xbfdbfe, 0.9);
				g.drawRect(-3, -3, 6, 3);
				g.endFill();
				return g;
			case 3: default: // bus and unknown — original two-window rectangle
				g.beginFill(fill, 1);
				g.lineStyle(1, 0x111111, 0.7);
				g.drawRoundedRect(-7, -4, 14, 8, 1.5);
				g.endFill();
				g.beginFill(0xbfdbfe, 0.9);
				g.drawRect(-5, -2.5, 3, 2);
				g.drawRect(2, -2.5, 3, 2);
				g.endFill();
				return g;
		}
	}
}

function haversineMetres(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const R = 6_371_000;
	const phi1 = (lat1 * Math.PI) / 180;
	const phi2 = (lat2 * Math.PI) / 180;
	const dPhi = ((lat2 - lat1) * Math.PI) / 180;
	const dLambda = ((lon2 - lon1) * Math.PI) / 180;
	const a = Math.sin(dPhi / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) ** 2;
	return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function parseHexColour(hex: string): number {
	const trimmed = hex.startsWith("#") ? hex.substring(1) : hex;
	const value = parseInt(trimmed, 16);
	return isNaN(value) ? 0xfacc15 : value;
}

function escapeHtml(input: string): string {
	return input
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/"/g, "&quot;");
}

function formatRouteLabel(route: TransitRoute): string {
	if (route.shortName && route.longName) {
		return `${route.shortName} · ${route.longName}`;
	}
	return route.shortName ?? route.longName ?? route.routeId;
}

