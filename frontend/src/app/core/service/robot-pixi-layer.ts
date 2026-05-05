/**
 * Phase-12 PixiJS-backed vehicle layer. Renders the live in-flight robots from
 * {@link VehicleService} as cute sprite badges on a single WebGL canvas overlay,
 * replacing the old per-robot Leaflet DOM marker. Drawn fresh each animation frame
 * so motion is buttery-smooth even at 256× game speed.
 *
 * <p>Strategy:
 * <ol>
 *   <li>One {@code L.pixiOverlay} container, one {@code PIXI.Container} of robot
 *       graphics keyed by vehicle id. Diff per frame — add new, update positions of
 *       persistent, remove arrived.</li>
 *   <li>Click hit-testing is layered on top via invisible {@code Leaflet.circleMarker}
 *       proxies — Pixi's interaction system doesn't co-operate cleanly with Leaflet's
 *       pane stacking, so we keep the click model in Leaflet's world.</li>
 *   <li>The layer ticks itself off {@code requestAnimationFrame} while any robot is
 *       in flight, so even a stationary game-clock at 1× still produces a smooth
 *       interpolation. Idles when the vehicle map is empty.</li>
 * </ol>
 */
import "leaflet-pixi-overlay";
import * as Leaflet from "leaflet";
import * as PIXI from "pixi.js";

import type {Vehicle, VehicleKind} from "../model/vehicle.model";

/** Pixi-overlay's render-callback signature (no @types package, so we declare locally). */
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

/** Snapshot of a robot's screen position the layer needs each frame. */
export interface RobotPosition {
	readonly id: string;
	readonly lat: number;
	readonly lon: number;
	readonly spoiled: boolean;
}

export interface RobotPixiLayerCallbacks {
	/** Resolve a robot's current `{lat, lon}` for the given game-minute. */
	resolvePosition(vehicle: Vehicle, gameMinutes: number): {readonly lat: number; readonly lon: number};
	/** Current game-minute. Read each frame to drive interpolation. */
	currentGameMinutes(): number;
	/** Snapshot of the live in-flight robots. */
	currentVehicles(): ReadonlyMap<string, Vehicle>;
	/** Player clicked the visible badge of {@code vehicleId}. */
	onRobotClick?(vehicleId: string): void;
}

/**
 * Manages the Pixi overlay + click-proxy markers. Construct once with the Leaflet map,
 * supply callbacks that read your VehicleService + ClockService, and call {@link start}
 * to begin the animation loop. {@link destroy} tears everything down on map dispose.
 */
export class RobotPixiLayer {
	private readonly graphics = new Map<string, PIXI.Graphics>();
	private readonly clickMarkers = new Map<string, Leaflet.CircleMarker>();
	private readonly pixiContainer: PIXI.Container;
	private readonly overlay: PixiOverlay;
	private readonly clickLayer: Leaflet.LayerGroup;
	private animationFrame: number | null = null;
	private destroyed = false;

	constructor(
		private readonly map: Leaflet.Map,
		private readonly callbacks: RobotPixiLayerCallbacks,
	) {
		if (typeof pixiOverlayFactory !== "function") {
			// Side-effect import of `leaflet-pixi-overlay` should have attached
			// `Leaflet.pixiOverlay`; if it didn't, the bundler likely tree-shook the
			// import or the module load order broke. Fail loudly so the next regression
			// is obvious in the console rather than "robots invisible, no errors".
			throw new Error(
				"RobotPixiLayer: leaflet-pixi-overlay plugin failed to attach `L.pixiOverlay` — "
					+ "check that `import 'leaflet-pixi-overlay'` ran before this file.",
			);
		}
		this.pixiContainer = new PIXI.Container();
		this.pixiContainer.sortableChildren = true;
		this.clickLayer = Leaflet.layerGroup().addTo(this.map);
		// Phase-13: ensure robots render ABOVE zone polygons (which live in the default
		// overlayPane, z-index 400) and the building markers (markerPane, z-index 600).
		// We create a dedicated pane sitting just above the marker pane so the robot
		// sprites are always visible no matter what else the map is showing.
		const ROBOT_PANE = "robotPane";
		if (!this.map.getPane(ROBOT_PANE)) {
			const pane = this.map.createPane(ROBOT_PANE);
			pane.style.zIndex = "650";
			pane.style.pointerEvents = "none";
		}
		this.overlay = pixiOverlayFactory(
			(utils) => this.drawFrame(utils),
			this.pixiContainer,
			{autoPreventDefault: false, pane: ROBOT_PANE},
		);
		this.overlay.addTo(this.map);
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
		this.map.removeLayer(this.clickLayer);
		for (const graphic of this.graphics.values()) {
			graphic.destroy();
		}
		this.graphics.clear();
		this.clickMarkers.clear();
		this.pixiContainer.destroy({children: true});
	}

	/** Pixi-overlay redraw callback. Diffs the {@link Vehicle} map against the existing
	 * sprite cache, adds/updates/removes accordingly, then re-renders the WebGL surface. */
	private drawFrame(utils: PixiOverlayUtils): void {
		const vehicles = this.callbacks.currentVehicles();
		const now = this.callbacks.currentGameMinutes();
		const liveIds = new Set<string>();
		const scale = 1 / utils.getScale(); // counter-zoom so badges stay constant size

		for (const vehicle of vehicles.values()) {
			liveIds.add(vehicle.id);
			const {lat, lon} = this.callbacks.resolvePosition(vehicle, now);
			const point = utils.latLngToLayerPoint([lat, lon]);
			const spoiled = vehicle.spoilageDeadlineGameMinutes !== null
				&& now > vehicle.spoilageDeadlineGameMinutes;

			let graphic = this.graphics.get(vehicle.id);
			if (!graphic) {
				graphic = RobotPixiLayer.makeBadge(vehicle.kind, spoiled);
				graphic.zIndex = vehicle.kind === "BUS" ? 11 : 10;
				this.graphics.set(vehicle.id, graphic);
				this.pixiContainer.addChild(graphic);
			} else {
				RobotPixiLayer.repaintBadge(graphic, vehicle.kind, spoiled);
			}
			graphic.position.set(point.x, point.y);
			graphic.scale.set(scale, scale);
			// Phase-17: visually distinguish a loading vehicle (still at the source,
			// hasn't departed yet) by dimming the badge. Once the depart minute passes
			// it pops back to full opacity and starts moving.
			graphic.alpha = now < vehicle.departsAtGameMinutes ? 0.45 : 1.0;

			let click = this.clickMarkers.get(vehicle.id);
			if (!click) {
				click = Leaflet.circleMarker([lat, lon], {
					radius: 12,
					stroke: false,
					fillOpacity: 0,
					interactive: true,
					bubblingMouseEvents: false,
				});
				click.on("click", (event) => {
					Leaflet.DomEvent.stopPropagation(event);
					this.callbacks.onRobotClick?.(vehicle.id);
				});
				click.addTo(this.clickLayer);
				this.clickMarkers.set(vehicle.id, click);
			} else {
				click.setLatLng([lat, lon]);
			}
		}

		// Drop sprites + click proxies for arrived/removed robots.
		for (const [id, graphic] of this.graphics) {
			if (liveIds.has(id)) {
				continue;
			}
			this.pixiContainer.removeChild(graphic);
			graphic.destroy();
			this.graphics.delete(id);
			const click = this.clickMarkers.get(id);
			if (click) {
				this.clickLayer.removeLayer(click);
				this.clickMarkers.delete(id);
			}
		}

		utils.getRenderer().render(this.pixiContainer);
	}

	/**
	 * Draw a vehicle badge. {@code ROBOT} renders as a soft-blue rounded body with
	 * an antenna and friendly eyes; {@code BUS} renders as a bigger sunshine-yellow
	 * rectangle with a windshield strip + window panes so the player can spot the
	 * GTFS-scheduled middle leg of a multi-leg shipment at a glance. Spoiled cargo
	 * flips the body to a dark red regardless of kind.
	 */
	private static makeBadge(kind: VehicleKind, spoiled: boolean): PIXI.Graphics {
		const graphic = new PIXI.Graphics();
		RobotPixiLayer.repaintBadge(graphic, kind, spoiled);
		return graphic;
	}

	private static repaintBadge(
		graphic: PIXI.Graphics,
		kind: VehicleKind,
		spoiled: boolean,
	): void {
		graphic.clear();
		if (kind === "BUS") {
			RobotPixiLayer.paintBus(graphic, spoiled);
			return;
		}
		RobotPixiLayer.paintRobot(graphic, spoiled);
	}

	private static paintRobot(graphic: PIXI.Graphics, spoiled: boolean): void {
		const bodyColor = spoiled ? 0x8a1c1c : 0x6c8cff;
		const outline = spoiled ? 0xffd6d6 : 0xffffff;
		// Outer body: rounded rect for a "robot torso" silhouette.
		graphic.lineStyle(2, outline, 1);
		graphic.beginFill(bodyColor, 1);
		graphic.drawRoundedRect(-9, -7, 18, 14, 4);
		graphic.endFill();
		// Antenna stem + tip.
		graphic.lineStyle(2, outline, 1);
		graphic.moveTo(0, -7);
		graphic.lineTo(0, -11);
		graphic.lineStyle(0);
		graphic.beginFill(outline, 1);
		graphic.drawCircle(0, -12, 1.6);
		graphic.endFill();
		// Two eyes.
		graphic.beginFill(outline, 1);
		graphic.drawCircle(-3.5, -1, 1.6);
		graphic.drawCircle(3.5, -1, 1.6);
		graphic.endFill();
		// A subtle smile.
		graphic.lineStyle(1, outline, 0.8);
		graphic.moveTo(-3, 3);
		graphic.quadraticCurveTo(0, 5, 3, 3);
	}

	private static paintBus(graphic: PIXI.Graphics, spoiled: boolean): void {
		const bodyColor = spoiled ? 0x8a1c1c : 0xf5b400;
		const outline = spoiled ? 0xffd6d6 : 0x222222;
		const window = spoiled ? 0xffd6d6 : 0xb6e0ff;
		// Bus body: longer rounded rectangle.
		graphic.lineStyle(2, outline, 1);
		graphic.beginFill(bodyColor, 1);
		graphic.drawRoundedRect(-13, -8, 26, 16, 3);
		graphic.endFill();
		// Three side windows.
		graphic.lineStyle(1, outline, 1);
		graphic.beginFill(window, 1);
		graphic.drawRect(-10, -5, 6, 5);
		graphic.drawRect(-2.5, -5, 6, 5);
		graphic.drawRect(5, -5, 6, 5);
		graphic.endFill();
		// Two wheels (negative-y so they sit at the body bottom).
		graphic.lineStyle(0);
		graphic.beginFill(outline, 1);
		graphic.drawCircle(-7, 8, 2);
		graphic.drawCircle(7, 8, 2);
		graphic.endFill();
	}
}

