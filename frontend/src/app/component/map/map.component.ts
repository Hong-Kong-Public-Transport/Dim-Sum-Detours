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
import {DeliveryAnimation, DeliveryService} from "../../core/service/delivery.service";
import {GameService} from "../../core/service/game.service";
import {GtfsService} from "../../core/service/gtfs.service";
import {LanguageService} from "../../core/service/language.service";
import {OsmService} from "../../core/service/osm.service";
import {RestaurantService} from "../../core/service/restaurant.service";
import {RestaurantSpawnerService} from "../../core/service/restaurant-spawner.service";
import {ThemeService} from "../../core/service/theme.service";
import {formatMoney} from "../../core/utility/format-locale";
import {isValidPlacement, respectsSpacing} from "../../core/utility/placement-validator";
import {FactoryOperationsDrawerComponent} from "../factory-operations-drawer/factory-operations-drawer.component";
import {FarmStatsDrawerComponent} from "../farm-stats-drawer/farm-stats-drawer.component";
import {PanelComponent} from "../panel/panel.component";
import {RecipePickerDialogComponent} from "../recipe-picker-dialog/recipe-picker-dialog.component";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";
import {RestaurantPanelDrawerComponent} from "../restaurant-panel-drawer/restaurant-panel-drawer.component";
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
	private readonly deliveryService = inject(DeliveryService);
	private readonly gameService = inject(GameService);
	private readonly gtfsService = inject(GtfsService);
	private readonly osmService = inject(OsmService);
	private readonly languageService = inject(LanguageService);
	private readonly restaurantService = inject(RestaurantService);
	private readonly restaurantSpawner = inject(RestaurantSpawnerService);
	private readonly themeService = inject(ThemeService);
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

	private leafletMap?: Leaflet.Map;
	private tileLayer?: Leaflet.TileLayer;
	private placementLayer?: Leaflet.GeoJSON;
	private buildingsLayer?: Leaflet.LayerGroup;
	private deliveriesLayer?: Leaflet.LayerGroup;

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

		effect(() => {
			const list = this.buildings();
			if (!this.leafletMap || !this.buildingsLayer) {
				return;
			}
			this.buildingsLayer.clearLayers();
			for (const building of list) {
				this.buildingsLayer.addLayer(this.makeBuildingMarker(building));
			}
		});

		// Re-bind zone tooltips whenever the active language changes — Leaflet caches the
		// originally-bound text per layer so we have to rebind explicitly.
		effect(() => {
			this.languageService.activeLanguage();
			this.refreshZoneTooltips();
		});

		// Redraw the delivery markers whenever either the active animations or the game-clock
		// changes — the marker's lat/lon is interpolated off the elapsed game minutes.
		effect(() => {
			const animations = this.deliveryService.animations();
			const snapshot = this.clockService.snapshot();
			this.redrawDeliveries(animations, snapshot.gameMinutes);
		});

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

	protected onReorderOperations(newOperations: readonly string[]): void {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return;
		}
		this.gameService.updateFactoryOperations(factoryId, newOperations).subscribe({error: () => undefined});
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
		this.deliveriesLayer = Leaflet.layerGroup().addTo(this.leafletMap);

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
		const icon = Leaflet.divIcon({
			className: `building-marker ${cssKind}`,
			html: `<i class="pi ${iconClass}" aria-hidden="true"></i>`,
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
	 * Redraw every active delivery marker. Position is interpolated linearly between source
	 * and destination, parameterised by elapsed game minutes — the resulting marker speed
	 * automatically scales with the game-clock speed multiplier.
	 */
	private redrawDeliveries(animations: readonly DeliveryAnimation[], gameMinutes: number): void {
		const layer = this.deliveriesLayer;
		if (!layer) {
			return;
		}
		layer.clearLayers();
		for (const animation of animations) {
			const total = Math.max(1, animation.arrivesAtGameMinutes - animation.startedAtGameMinutes);
			const elapsed = Math.max(0, gameMinutes - animation.startedAtGameMinutes);
			const progress = Math.max(0, Math.min(1, elapsed / total));
			const lat = animation.fromLat + (animation.toLat - animation.fromLat) * progress;
			const lon = animation.fromLon + (animation.toLon - animation.fromLon) * progress;
			const icon = Leaflet.divIcon({
				className: "delivery-marker",
				html: `<i class="pi pi-truck" aria-hidden="true"></i>`,
				iconSize: [22, 22],
				iconAnchor: [11, 11],
			});
			const tooltipText = this.translocoService.translate("map.tooltip.delivery");
			Leaflet.marker([lat, lon], {icon, title: tooltipText})
				.bindTooltip(tooltipText, {direction: "top", offset: [0, -8]})
				.addTo(layer);
		}
	}
}

/** Minimal shape Leaflet hands to the {@code onEachFeature} callback for our zones. */
interface ZoneFeature {
	readonly properties?: {readonly kind?: PlacementZoneKind; readonly name?: string};
}
