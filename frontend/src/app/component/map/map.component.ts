import {AfterViewInit, ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, OnDestroy, signal, viewChild} from "@angular/core";
import {FormsModule} from "@angular/forms";

import {TranslocoDirective, TranslocoService} from "@jsverse/transloco";
import * as Leaflet from "leaflet";
import {ButtonModule} from "primeng/button";
import {MessageModule} from "primeng/message";
import {SelectModule} from "primeng/select";
import {TooltipModule} from "primeng/tooltip";

import {GAME_CONSTANTS} from "../../core/constant/game.constants";
import {localize} from "../../core/i18n/localize";
import {Building, BuildingKind} from "../../core/model/building.model";
import {FeatureCollection, PlacementZoneKind} from "../../core/model/geojson.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {RestaurantTemplate} from "../../core/model/restaurant-template.model";
import {ClockService} from "../../core/service/clock.service";
import {ContentService} from "../../core/service/content.service";
import {GameService} from "../../core/service/game.service";
import {GtfsService} from "../../core/service/gtfs.service";
import {LanguageService} from "../../core/service/language.service";
import {OsmService} from "../../core/service/osm.service";
import {RestaurantService} from "../../core/service/restaurant.service";
import {RestaurantSpawnerService} from "../../core/service/restaurant-spawner.service";
import {ThemeService} from "../../core/service/theme.service";
import {VehicleService} from "../../core/service/vehicle.service";
import type {Vehicle} from "../../core/model/vehicle.model";
import {RobotPixiLayer} from "../../core/service/robot-pixi-layer";
import {formatMoney} from "../../core/utility/format-locale";
import {isValidPlacement, respectsSpacing} from "../../core/utility/placement-validator";
import {FactoryOperationsDrawerComponent} from "../factory-operations-drawer/factory-operations-drawer.component";
import {FarmStatsDrawerComponent} from "../farm-stats-drawer/farm-stats-drawer.component";
import {PanelComponent} from "../panel/panel.component";
import {RecipePickerDialogComponent} from "../recipe-picker-dialog/recipe-picker-dialog.component";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";
import {RestaurantPanelDrawerComponent} from "../restaurant-panel-drawer/restaurant-panel-drawer.component";
import {RobotDrawerComponent} from "../robot-drawer/robot-drawer.component";
import {SearchBoxComponent} from "../search-box/search-box.component";

interface IngredientView {
	readonly id: string;
	readonly name: string;
	readonly category: string;
}

/** The subset of {@link BuildingKind} that the player can place directly. Restaurants spawn automatically. */
type PlaceableBuildingKind = Extract<BuildingKind, "FARM" | "FACTORY">;

interface FeedOption {
	readonly label: string;
	readonly value: string;
}

@Component({
	selector: "app-map",
	imports: [
		ButtonModule,
		FactoryOperationsDrawerComponent,
		FarmStatsDrawerComponent,
		FormsModule,
		MessageModule,
		PanelComponent,
		RecipePickerDialogComponent,
		RecipeTileComponent,
		RestaurantPanelDrawerComponent,
		RobotDrawerComponent,
		SearchBoxComponent,
		SelectModule,
		TooltipModule,
		TranslocoDirective,
	],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./map.component.html",
	styleUrl: "./map.component.scss",
})
export class MapComponent implements AfterViewInit, OnDestroy {
	private readonly mapElement = viewChild.required<ElementRef<HTMLDivElement>>("mapElement");
	private readonly clockService = inject(ClockService);
	private readonly contentService = inject(ContentService);
	private readonly gameService = inject(GameService);
	private readonly gtfsService = inject(GtfsService);
	private readonly osmService = inject(OsmService);
	private readonly languageService = inject(LanguageService);
	private readonly restaurantService = inject(RestaurantService);
	private readonly restaurantSpawner = inject(RestaurantSpawnerService);
	private readonly themeService = inject(ThemeService);
	private readonly vehicleService = inject(VehicleService);
	private readonly translocoService = inject(TranslocoService);

	protected readonly ingredients = signal<readonly Ingredient[]>([]);
	protected readonly recipes = signal<readonly Recipe[]>([]);
	protected readonly operations = signal<readonly Operation[]>([]);
	protected readonly restaurantTemplates = signal<readonly RestaurantTemplate[]>([]);
	protected readonly feeds = signal<readonly string[]>([]);
	protected readonly selectedFeed = signal<string | null>(null);
	protected readonly loadingZones = signal<boolean>(false);
	protected readonly placementZones = signal<FeatureCollection | null>(null);

	/** Build flow: pickerKind opens the recipe modal; buildMode is set once a recipe is picked. */
	protected readonly pickerKind = signal<PlaceableBuildingKind | null>(null);
	protected readonly buildMode = signal<PlaceableBuildingKind | null>(null);
	protected readonly selectedRecipeId = signal<string | null>(null);
	protected readonly placementError = signal<string | null>(null);

	protected readonly hoverValid = signal<boolean>(true);
	protected readonly selectedFactoryId = signal<string | null>(null);
	protected readonly selectedFarmId = signal<string | null>(null);
	protected readonly selectedRestaurantId = signal<string | null>(null);
	protected readonly selectedRobotId = signal<string | null>(null);

	/** Sidebar/dialog filter strings. Plain substring filter over the localised name. */
	protected readonly ingredientFilter = signal<string>("");
	protected readonly recipeFilter = signal<string>("");

	protected readonly buildings = this.gameService.buildings;
	protected readonly balance = this.gameService.balance;

	protected readonly farmCost = GAME_CONSTANTS.economy.farmBuildCost;
	protected readonly factoryCost = GAME_CONSTANTS.economy.factoryBuildCost;
	protected readonly farmCostFormatted = formatMoney(this.farmCost);
	protected readonly factoryCostFormatted = formatMoney(this.factoryCost);

	protected readonly canAffordFarm = computed(() => this.balance() >= this.farmCost);
	protected readonly canAffordFactory = computed(() => this.balance() >= this.factoryCost);

	protected readonly feedOptions = computed<FeedOption[]>(() =>
		this.feeds().map((name) => ({label: name, value: name})),
	);

	protected readonly ingredientViews = computed<readonly IngredientView[]>(() => {
		const language = this.languageService.activeLanguage();
		return this.ingredients().map((ingredient) => ({
			id: ingredient.id,
			name: localize(ingredient.displayName, language),
			category: ingredient.category,
		}));
	});

	/** Ingredient views narrowed by the sidebar search box. */
	protected readonly filteredIngredientViews = computed<readonly IngredientView[]>(() => {
		const needle = this.ingredientFilter().trim().toLowerCase();
		if (!needle) {
			return this.ingredientViews();
		}
		return this.ingredientViews().filter((view) =>
			view.name.toLowerCase().includes(needle) || view.id.toLowerCase().includes(needle),
		);
	});

	/** Recipes narrowed by the sidebar search box (matches localised name + id). */
	protected readonly filteredRecipes = computed<readonly Recipe[]>(() => {
		const needle = this.recipeFilter().trim().toLowerCase();
		if (!needle) {
			return this.recipes();
		}
		const language = this.languageService.activeLanguage();
		return this.recipes().filter((recipe) => {
			const name = localize(recipe.displayName, language).toLowerCase();
			return name.includes(needle) || recipe.id.toLowerCase().includes(needle);
		});
	});

	/** Currently-selected factory, exposed to the right-edge drawer. */
	protected readonly selectedFactory = computed<Building | null>(() => {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return null;
		}
		return this.buildings().find((candidate) => candidate.id === factoryId) ?? null;
	});

	/** Currently-selected farm, exposed to the right-edge stats drawer. */
	protected readonly selectedFarm = computed<Building | null>(() => {
		const farmId = this.selectedFarmId();
		if (!farmId) {
			return null;
		}
		return this.buildings().find((candidate) => candidate.id === farmId) ?? null;
	});

	/** Currently-selected restaurant, exposed to the right-edge order drawer. */
	protected readonly selectedRestaurant = computed<Building | null>(() => {
		const restaurantId = this.selectedRestaurantId();
		if (!restaurantId) {
			return null;
		}
		return this.buildings().find((candidate) => candidate.id === restaurantId) ?? null;
	});

	/** Currently-selected in-flight robot, exposed to the right-edge robot drawer. The
	 * computed re-derives off {@link VehicleService.vehicles} so an arrival event (which
	 * removes the entry from that map) auto-closes this drawer. */
	protected readonly selectedRobot = computed<Vehicle | null>(() => {
		const id = this.selectedRobotId();
		if (!id) {
			return null;
		}
		return this.vehicleService.vehicles().get(id) ?? null;
	});

	private leafletMap?: Leaflet.Map;
	private tileLayer?: Leaflet.TileLayer;
	private placementLayer?: Leaflet.GeoJSON;
	private buildingsLayer?: Leaflet.LayerGroup;
	/** Phase-12: WebGL-backed robot overlay. Rendered on its own Pixi container so
	 * the moving badges don't trash Leaflet's DOM marker pane every frame. */
	private robotLayer?: RobotPixiLayer;
	/** Per-building Leaflet marker reference. Lets us update the production-progress CSS
	 * variable each tick without recreating the marker (which would clobber tooltips +
	 * any in-flight click handlers). */
	private readonly buildingMarkers = new Map<string, Leaflet.Marker>();
	/** Cached "closed" flag per restaurant marker so we can detect when the icon HTML needs
	 * a full rebuild (close-state changes the colour, no other live property affects the SVG). */
	private readonly markerClosedState = new Map<string, boolean>();
	/** Cached "stalled" flag per factory marker — see {@link markerClosedState}. */
	private readonly markerStalledState = new Map<string, boolean>();
	/** Last wall-clock millisecond on which {@link gameService#refreshBuildings} ran;
	 * gates the polling effect so the producedUnits counter on the farm/factory drawers
	 * stays live without spamming the backend on every clock SSE frame (or — worse —
	 * scaling the poll rate with game speed). */
	private lastBuildingsRefreshWallMs = 0;

	constructor() {
		effect(() => {
			const tileUrl = this.themeService.mapTileUrl();
			if (this.tileLayer) {
				this.tileLayer.setUrl(tileUrl);
			}
		});

		effect(() => {
			const feed = this.selectedFeed();
			if (!feed || !this.leafletMap) {
				return;
			}
			this.loadFeedLayers(feed);
		});

		// Diff the building list against the existing marker map: remove markers whose
		// building was demolished, add markers for newly-placed buildings, and replace
		// markers whose closed-state changed (changes the icon colour). We do NOT recreate
		// markers on production-cycle ticks — those are pushed via a CSS variable so the
		// click handler / tooltip stay attached.
		effect(() => {
			const list = this.buildings();
			const layer = this.buildingsLayer;
			if (!this.leafletMap || !layer) {
				return;
			}
			const liveIds = new Set<string>();
			for (const building of list) {
				liveIds.add(building.id);
				const newClosed = (building.kind === "RESTAURANT" && building.closed) === true;
				const newStalled = (building.kind === "FACTORY" && building.stalled) === true;
				const existing = this.buildingMarkers.get(building.id);
				if (!existing) {
					const marker = this.makeBuildingMarker(building);
					this.buildingMarkers.set(building.id, marker);
					this.markerClosedState.set(building.id, newClosed);
					this.markerStalledState.set(building.id, newStalled);
					layer.addLayer(marker);
					continue;
				}
				const closedChanged = this.markerClosedState.get(building.id) !== newClosed;
				const stalledChanged = this.markerStalledState.get(building.id) !== newStalled;
				if (closedChanged || stalledChanged) {
					layer.removeLayer(existing);
					const marker = this.makeBuildingMarker(building);
					this.buildingMarkers.set(building.id, marker);
					this.markerClosedState.set(building.id, newClosed);
					this.markerStalledState.set(building.id, newStalled);
					layer.addLayer(marker);
				}
			}
			for (const [id, marker] of this.buildingMarkers) {
				if (!liveIds.has(id)) {
					layer.removeLayer(marker);
					this.buildingMarkers.delete(id);
					this.markerClosedState.delete(id);
					this.markerStalledState.delete(id);
				}
			}
		});

		// Phase-8: every game-clock tick, push the current production-cycle progress onto
		// each farm/factory marker via a CSS variable. The marker's `.production-ring` child
		// reads the variable to size its conic-gradient sweep — no Angular re-render needed.
		effect(() => {
			const now = this.clockService.snapshot().gameMinutes;
			for (const building of this.buildings()) {
				if (building.kind === "RESTAURANT") {
					continue;
				}
				const marker = this.buildingMarkers.get(building.id);
				if (!marker) {
					continue;
				}
				const cycleStart = building.cycleStartedAtGameMinutes ?? 0;
				const duration = building.cycleDurationGameMinutes ?? 0;
				if (duration <= 0) {
					continue;
				}
				const elapsed = Math.max(0, now - cycleStart);
				const progress = (elapsed % duration) / duration;
				const element = marker.getElement();
				if (element) {
					element.style.setProperty("--progress", `${progress}`);
				}
			}
			// Phase-13: refresh /api/game/buildings on a *wall-clock* cadence — every 2
			// seconds of real time, regardless of game speed. The previous game-minute
			// bucket was problematic at 256× speed (a "1 game-minute" bucket fires
			// every ~4ms wall-clock and floods the backend). Wall-clock pacing keeps
			// the load constant; the producedUnits counter visibly increments because
			// at 1× speed a 10-game-min farm cycle finishes in 10 real seconds, well
			// inside the 2s polling window.
			const wallNow = Date.now();
			if (wallNow - this.lastBuildingsRefreshWallMs >= 2000) {
				this.lastBuildingsRefreshWallMs = wallNow;
				this.gameService.refreshBuildings().subscribe({error: () => undefined});
			}
		});

		// Re-bind zone tooltips whenever the active language changes — Leaflet caches the
		// originally-bound text per layer so we have to rebind explicitly.
		effect(() => {
			this.languageService.activeLanguage();
			this.refreshZoneTooltips();
		});

		// Phase-12: robot rendering is owned by RobotPixiLayer (PixiJS overlay) which
		// drives its own RAF loop and reads {@link VehicleService.vehicles} +
		// {@link ClockService.snapshot} via the callbacks supplied at construction.
		// No per-clock-tick redraw effect needed — the WebGL surface keeps animating
		// even between sim ticks.

		// Auto-spawn restaurants once both placement zones and templates are available. The
		// spawner itself guards against repeat invocation and re-arms on game reset.
		effect(() => {
			const zones = this.placementZones();
			const templates = this.restaurantTemplates();
			// Tracking these signals keeps the effect re-firing after a reset (which clears
			// buildings + bumps resetCount) and once the initial buildings refresh resolves.
			this.gameService.buildingsLoaded();
			this.gameService.resetCount();
			if (!zones || templates.length === 0) {
				return;
			}
			this.restaurantSpawner.spawnFromZones(zones, templates, this.buildings());
		});
	}

	ngAfterViewInit(): void {
		this.initializeMap();
		this.contentService.listIngredients().subscribe((list) => this.ingredients.set(list));
		this.contentService.listRecipes().subscribe((list) => this.recipes.set(list));
		this.contentService.listOperations().subscribe((list) => this.operations.set(list));
		this.contentService.listRestaurantTemplates().subscribe((list) => this.restaurantTemplates.set(list));
		this.gtfsService.listFeeds().subscribe((list) => {
			this.feeds.set(list);
			if (list.length > 0 && this.selectedFeed() === null) {
				this.selectedFeed.set(list[0]);
			}
		});
		this.gameService.refreshBuildings().subscribe({error: () => undefined});
		this.gameService.refreshBalance().subscribe({error: () => undefined});
		this.restaurantService.refreshOrders().subscribe({error: () => undefined});
	}

	ngOnDestroy(): void {
		this.robotLayer?.destroy();
		this.leafletMap?.remove();
	}

	protected setSelectedFeed(value: string | null): void {
		this.selectedFeed.set(value);
	}

	/** Step 1 of the placement flow: open the recipe-picker modal. */
	protected openRecipePicker(kind: PlaceableBuildingKind): void {
		if (this.buildMode() !== null) {
			return;
		}
		this.placementError.set(null);
		this.pickerKind.set(kind);
	}

	/** Step 2: recipe picked — close the modal and arm the map for placement. */
	protected onRecipePicked(recipeId: string): void {
		const kind = this.pickerKind();
		if (!kind) {
			return;
		}
		this.selectedRecipeId.set(recipeId);
		this.buildMode.set(kind);
		this.pickerKind.set(null);
	}

	protected onRecipeCancelled(): void {
		this.pickerKind.set(null);
	}

	protected cancelPlacement(): void {
		this.buildMode.set(null);
		this.selectedRecipeId.set(null);
		this.placementError.set(null);
	}

	protected closeFactoryPanel(): void {
		this.selectedFactoryId.set(null);
	}

	protected closeFarmPanel(): void {
		this.selectedFarmId.set(null);
	}

	protected closeRestaurantPanel(): void {
		this.selectedRestaurantId.set(null);
	}

	protected closeRobotPanel(): void {
		this.selectedRobotId.set(null);
	}

	protected onReorderOperations(newOperations: readonly string[]): void {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return;
		}
		this.gameService.updateFactoryOperations(factoryId, newOperations).subscribe({error: () => undefined});
	}

	/** Phase-9 beta-polish: spend the refrigeration upgrade fee on the currently-open
	 * factory. The drawer surfaces a 402 (broke) silently — the player just keeps seeing
	 * the upgrade button. A 404 also collapses to no-op since the drawer would close on
	 * the next building-list refresh anyway. */
	protected onRefrigerateFactory(): void {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return;
		}
		this.gameService.refrigerateFactory(factoryId).subscribe({error: () => undefined});
	}

	private initializeMap(): void {
		const {fallbackCenter, defaultZoom, minZoom, maxZoom, tileAttribution} = GAME_CONSTANTS.map;

		this.leafletMap = Leaflet.map(this.mapElement().nativeElement, {
			center: [fallbackCenter.lat, fallbackCenter.lng],
			zoom: defaultZoom,
			minZoom,
			maxZoom,
			preferCanvas: true,
		});

		this.tileLayer = Leaflet.tileLayer(this.themeService.mapTileUrl(), {
			attribution: tileAttribution,
			maxZoom,
		}).addTo(this.leafletMap);

		this.buildingsLayer = Leaflet.layerGroup().addTo(this.leafletMap);
		this.robotLayer = new RobotPixiLayer(this.leafletMap, {
			currentVehicles: () => this.vehicleService.vehicles(),
			// Phase-13: read the wall-clock-extrapolated game-minute (not the stepped
			// per-tick snapshot) so the RAF loop interpolates robot positions smoothly
			// between server SSE frames. The signal-backed `snapshot().gameMinutes`
			// only updates every SIM_TICK_MILLIS = 100ms, which makes 60fps motion look
			// stuttery; `liveGameMinutes()` is recomputed off `performance.now()`
			// each frame and stays signal-free so it doesn't churn change detection.
			currentGameMinutes: () => this.clockService.liveGameMinutes(),
			resolvePosition: (vehicle, gameMinutes) =>
				this.vehicleService.interpolatePosition(vehicle, gameMinutes),
			onRobotClick: (vehicleId) => {
				this.selectedFactoryId.set(null);
				this.selectedFarmId.set(null);
				this.selectedRestaurantId.set(null);
				this.selectedRobotId.set(vehicleId);
			},
		});
		this.robotLayer.start();

		this.leafletMap.on("click", (event) => {
			const kind = this.buildMode();
			const recipeId = this.selectedRecipeId();
			if (kind === null || recipeId === null) {
				return;
			}
			const lat = event.latlng.lat;
			const lon = event.latlng.lng;
			if (!isValidPlacement(kind, lat, lon, this.placementZones())) {
				this.placementError.set("sidebar.build.errors.INVALID_PLACEMENT_LOCATION");
				return;
			}
			if (!respectsSpacing(kind, lat, lon, this.buildings(), GAME_CONSTANTS.placement.minBuildingSpacingMeters)) {
				this.placementError.set("sidebar.build.errors.TOO_CLOSE_TO_EXISTING_BUILDING");
				return;
			}
			this.placementError.set(null);
			this.gameService.placeBuilding({kind, lat, lon, recipeId}).subscribe({
				next: () => {
					this.buildMode.set(null);
					this.selectedRecipeId.set(null);
					// Phase-13: removed the auto-resume-on-first-placement behaviour. Players
					// reported it as surprising — if they paused deliberately to plan a
					// layout, they want the clock to stay paused until they explicitly hit
					// play. The clock controls remain the single source of truth for
					// playing-vs-paused.
				},
				error: (error: {error?: {error?: string | null} | null} | null) => {
					const code = error?.error?.error ?? "UNKNOWN_ERROR";
					this.placementError.set(`sidebar.build.errors.${code}`);
					this.buildMode.set(null);
					this.selectedRecipeId.set(null);
				},
			});
		});

		this.leafletMap.on("mousemove", (event) => {
			const kind = this.buildMode();
			if (kind === null) {
				return;
			}
			const lat = event.latlng.lat;
			const lon = event.latlng.lng;
			const zonesOk = isValidPlacement(kind, lat, lon, this.placementZones());
			const spacingOk = respectsSpacing(kind, lat, lon, this.buildings(), GAME_CONSTANTS.placement.minBuildingSpacingMeters);
			this.hoverValid.set(zonesOk && spacingOk);
		});
	}

	private makeBuildingMarker(building: Building): Leaflet.Marker {
		const cssKind = MapComponent.cssClassFor(building.kind);
		const iconClass = MapComponent.iconClassFor(building.kind);
		const closedSuffix = (building.kind === "RESTAURANT" && building.closed) ? " closed" : "";
		const stalledSuffix = (building.kind === "FACTORY" && building.stalled) ? " stalled" : "";
		// Phase-8: farms / factories carry a `.production-ring` overlay whose conic-gradient
		// sweep is driven by the `--progress` CSS variable. Restaurants don't produce so they
		// skip the ring entirely.
		const ringHtml = (building.kind === "RESTAURANT")
			? ""
			: `<span class="production-ring" aria-hidden="true"></span>`;
		const icon = Leaflet.divIcon({
			className: `building-marker ${cssKind}${closedSuffix}${stalledSuffix}`,
			html: `${ringHtml}<i class="pi ${iconClass}" aria-hidden="true"></i>`,
			iconSize: [28, 28],
			iconAnchor: [14, 14],
		});
		const recipe = this.recipes().find((candidate) => candidate.id === building.recipeId);
		const recipeName = recipe
			? localize(recipe.displayName, this.languageService.activeLanguage())
			: building.recipeId;
		const tooltipText = `${this.translocoService.translate(`map.tooltip.${cssKind}`)}: ${recipeName}`;
		const marker = Leaflet.marker([building.lat, building.lon], {icon, title: recipeName})
			.bindTooltip(tooltipText, {direction: "top", offset: [0, -8]});
		marker.on("click", (event) => {
			Leaflet.DomEvent.stopPropagation(event);
			if (building.kind === "FACTORY") {
				this.selectedFarmId.set(null);
				this.selectedRestaurantId.set(null);
				this.selectedFactoryId.set(building.id);
			} else if (building.kind === "FARM") {
				this.selectedFactoryId.set(null);
				this.selectedRestaurantId.set(null);
				this.selectedFarmId.set(building.id);
			} else {
				this.selectedFactoryId.set(null);
				this.selectedFarmId.set(null);
				this.selectedRestaurantId.set(building.id);
			}
		});
		return marker;
	}

	private static iconClassFor(kind: BuildingKind): string {
		// PrimeIcons 7 doesn't ship `pi-leaf` (added in 8.x); use `pi-apple` for farms instead.
		switch (kind) {
			case "FARM": return "pi-apple";
			case "FACTORY": return "pi-cog";
			case "RESTAURANT": return "pi-shop";
		}
	}

	private static cssClassFor(kind: BuildingKind): string {
		switch (kind) {
			case "FARM": return "farm";
			case "FACTORY": return "factory";
			case "RESTAURANT": return "restaurant";
		}
	}

	/**
	 * Cheap bounding-box area for z-order sorting. Walks every coordinate of any geometry
	 * (Polygon / MultiPolygon / LineString) and returns the lat/lon extent area. Good enough
	 * to ensure smaller zones render on top of larger overlapping zones.
	 */
	private static bboxArea(feature: {geometry?: {coordinates?: unknown}}): number {
		let minLat = Infinity, minLon = Infinity, maxLat = -Infinity, maxLon = -Infinity;
		const visit = (node: unknown): void => {
			if (Array.isArray(node)) {
				if (node.length >= 2 && typeof node[0] === "number" && typeof node[1] === "number") {
					const lon = node[0] as number;
					const lat = node[1] as number;
					if (lat < minLat) minLat = lat;
					if (lat > maxLat) maxLat = lat;
					if (lon < minLon) minLon = lon;
					if (lon > maxLon) maxLon = lon;
				} else {
					for (const child of node) {
						visit(child);
					}
				}
			}
		};
		visit(feature.geometry?.coordinates);
		if (!isFinite(minLat) || !isFinite(minLon)) {
			return 0;
		}
		return Math.max(0, maxLat - minLat) * Math.max(0, maxLon - minLon);
	}

	private styleForFeature(kind: PlacementZoneKind): Leaflet.PathOptions {
		const styles = GAME_CONSTANTS.map.layerStyles;
		return {...styles[kind]};
	}

	private loadFeedLayers(feed: string): void {
		const map = this.leafletMap;
		if (!map) {
			return;
		}
		this.gtfsService.feedBoundingBox(feed).subscribe({
			next: (boundingBox) => {
				map.fitBounds([
					[boundingBox.south, boundingBox.west],
					[boundingBox.north, boundingBox.east],
				]);
			},
			error: (error) => console.error("Failed to load bounding box for", feed, error),
		});

		this.loadingZones.set(true);
		this.placementLayer?.remove();
		this.placementLayer = undefined;
		this.placementZones.set(null);

		this.osmService.placementZonesByFeed(feed).subscribe({
			next: (collection) => {
				this.placementZones.set(collection);
				// Sort largest-area-first so smaller (often nested) zones are added LAST and
				// therefore drawn on top — otherwise a tiny park inside a big residential
				// block becomes unclickable / un-hoverable. Bounding-box area is a cheap
				// proxy that's plenty accurate for this z-ordering decision.
				const sortedFeatures = [...collection.features].sort(
					(a, b) => MapComponent.bboxArea(b) - MapComponent.bboxArea(a),
				);
				const sortedCollection = {...collection, features: sortedFeatures};
				this.placementLayer = Leaflet.geoJSON(sortedCollection as never, {
					style: (feature) => this.styleForFeature(
						(feature?.properties?.["kind"] as PlacementZoneKind) ?? "park",
					),
					onEachFeature: (feature, layer) => {
						layer.bindTooltip(this.tooltipForZoneFeature(feature as unknown as ZoneFeature), {
							sticky: true,
							direction: "top",
							offset: [0, -12],
						});
					},
				}).addTo(map);
				this.loadingZones.set(false);
			},
			error: (error) => {
				console.error("Failed to load placement zones for", feed, error);
				this.loadingZones.set(false);
			},
		});
	}

	private refreshZoneTooltips(): void {
		const layer = this.placementLayer;
		if (!layer) {
			return;
		}
		layer.eachLayer((sublayer) => {
			const feature = (sublayer as unknown as {feature?: ZoneFeature}).feature;
			(sublayer as Leaflet.Layer).unbindTooltip();
			(sublayer as Leaflet.Layer).bindTooltip(this.tooltipForZoneFeature(feature), {
				sticky: true,
				direction: "top",
				offset: [0, -12],
			});
		});
	}

	/** Format the hover-tooltip text: zone-kind label, plus the OSM `name` tag when present. */
	private tooltipForZoneFeature(feature: ZoneFeature | undefined): string {
		const properties = feature?.properties;
		const zoneKind = properties?.kind ?? "park";
		const kindLabel = this.translocoService.translate(`osm.zone.${zoneKind}`);
		const name = properties?.name;
		return name ? `${name} (${kindLabel})` : kindLabel;
	}

	/**
	 * Phase-12: redraw is handled by {@link RobotPixiLayer}; this method intentionally
	 * removed. Kept as a comment marker so a future search for "redrawVehicles" surfaces
	 * the migration note rather than a dangling dead method.
	 */
}

/** Minimal shape Leaflet hands to the {@code onEachFeature} callback for our zones. */
interface ZoneFeature {
	readonly properties?: {readonly kind?: PlacementZoneKind; readonly name?: string};
}
