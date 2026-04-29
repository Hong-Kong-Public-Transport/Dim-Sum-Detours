import {AfterViewInit, ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, OnDestroy, signal, viewChild} from "@angular/core";
import {FormsModule} from "@angular/forms";
import {TranslocoDirective, TranslocoService} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {MessageModule} from "primeng/message";
import {SelectModule} from "primeng/select";
import {TooltipModule} from "primeng/tooltip";
import * as Leaflet from "leaflet";

import {GAME_CONSTANTS} from "../../core/constant/game.constants";
import {ContentService} from "../../core/service/content.service";
import {GameService} from "../../core/service/game.service";
import {GtfsService} from "../../core/service/gtfs.service";
import {LanguageService} from "../../core/service/language.service";
import {OsmService} from "../../core/service/osm.service";
import {ThemeService} from "../../core/service/theme.service";
import {Building, BuildingKind} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {FeatureCollection, PlacementZoneKind} from "../../core/model/geojson.model";
import {localize} from "../../core/i18n/localize";
import {formatMoney} from "../../core/utility/format-money";
import {isValidPlacement} from "../../core/utility/placement-validator";
import {FactoryOperationsDrawerComponent} from "../factory-operations-drawer/factory-operations-drawer.component";
import {PanelComponent} from "../panel/panel.component";

interface IngredientView {
	readonly id: string;
	readonly name: string;
	readonly category: string;
}

interface RecipeView {
	readonly id: string;
	readonly name: string;
	readonly inputs: string;
	readonly outputs: string;
	readonly operations: string;
}

interface FeedOption {
	readonly label: string;
	readonly value: string;
}

interface RecipeOption {
	readonly label: string;
	readonly value: string;
}

@Component({
	selector: "app-map",
	imports: [
		TranslocoDirective,
		SelectModule,
		FormsModule,
		ButtonModule,
		MessageModule,
		TooltipModule,
		PanelComponent,
		FactoryOperationsDrawerComponent,
	],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./map.component.html",
	styleUrl: "./map.component.scss",
})
export class MapComponent implements AfterViewInit, OnDestroy {
	private readonly mapElement = viewChild.required<ElementRef<HTMLDivElement>>("mapElement");
	private readonly contentService = inject(ContentService);
	private readonly gameService = inject(GameService);
	private readonly gtfsService = inject(GtfsService);
	private readonly osmService = inject(OsmService);
	private readonly languageService = inject(LanguageService);
	private readonly themeService = inject(ThemeService);
	private readonly translocoService = inject(TranslocoService);

	protected readonly ingredients = signal<readonly Ingredient[]>([]);
	protected readonly recipes = signal<readonly Recipe[]>([]);
	protected readonly operations = signal<readonly Operation[]>([]);
	protected readonly feeds = signal<readonly string[]>([]);
	protected readonly selectedFeed = signal<string | null>(null);
	protected readonly loadingZones = signal<boolean>(false);
	protected readonly placementZones = signal<FeatureCollection | null>(null);

	protected readonly buildMode = signal<BuildingKind | null>(null);
	protected readonly pendingPlacement = signal<{lat: number; lng: number} | null>(null);
	protected readonly selectedRecipeId = signal<string | null>(null);
	protected readonly placementError = signal<string | null>(null);

	protected readonly hoverValid = signal<boolean>(true);
	protected readonly selectedFactoryId = signal<string | null>(null);

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

	protected readonly recipeOptions = computed<RecipeOption[]>(() => {
		const language = this.languageService.activeLanguage();
		const kind = this.buildMode();
		// Farms grow raw ingredients (recipes tagged "farm"); factories run everything else.
		const matchesKind = (recipe: Recipe): boolean => {
			const isFarmRecipe = recipe.tags.includes("farm");
			if (kind === "FARM") return isFarmRecipe;
			if (kind === "FACTORY") return !isFarmRecipe;
			return false;
		};
		return this.recipes()
			.filter((recipe) => recipe.minimumFactoryTier === 1)
			.filter(matchesKind)
			.map((recipe) => ({label: localize(recipe.displayName, language), value: recipe.id}));
	});

	protected readonly ingredientViews = computed<readonly IngredientView[]>(() => {
		const language = this.languageService.activeLanguage();
		return this.ingredients().map((ingredient) => ({
			id: ingredient.id,
			name: localize(ingredient.displayName, language),
			category: ingredient.category,
		}));
	});

	protected readonly recipeViews = computed<readonly RecipeView[]>(() => {
		const language = this.languageService.activeLanguage();
		const ingredientLookup = new Map<string, Ingredient>();
		for (const ingredient of this.ingredients()) {
			ingredientLookup.set(ingredient.id, ingredient);
		}

		const formatLine = (ingredientId: string, quantity: number): string => {
			const ingredient = ingredientLookup.get(ingredientId);
			const name = ingredient ? localize(ingredient.displayName, language) : ingredientId;
			return `${quantity}× ${name}`;
		};

		return this.recipes().map((recipe) => ({
			id: recipe.id,
			name: localize(recipe.displayName, language),
			inputs: recipe.inputs.length === 0
				? "—"
				: recipe.inputs.map((line) => formatLine(line.ingredientId, line.quantity)).join(" + "),
			outputs: recipe.outputs.map((line) => formatLine(line.ingredientId, line.quantity)).join(" + "),
			operations: recipe.operations.join(" → "),
		}));
	});

	protected readonly isPlacementValid = computed<boolean>(() => {
		const placement = this.pendingPlacement();
		const kind = this.buildMode();
		if (!placement || !kind) {
			return true;
		}
		return isValidPlacement(kind, placement.lat, placement.lng, this.placementZones());
	});

	/** Currently-selected building, exposed to the right-edge drawer. */
	protected readonly selectedFactory = computed<Building | null>(() => {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return null;
		}
		return this.buildings().find((candidate) => candidate.id === factoryId) ?? null;
	});

	private leafletMap?: Leaflet.Map;
	private tileLayer?: Leaflet.TileLayer;
	private placementLayer?: Leaflet.GeoJSON;
	private buildingsLayer?: Leaflet.LayerGroup;

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
	}

	ngAfterViewInit(): void {
		this.initialiseMap();
		this.contentService.listIngredients().subscribe((list) => this.ingredients.set(list));
		this.contentService.listRecipes().subscribe((list) => this.recipes.set(list));
		this.contentService.listOperations().subscribe((list) => this.operations.set(list));
		this.gtfsService.listFeeds().subscribe((list) => {
			this.feeds.set(list);
			if (list.length > 0 && this.selectedFeed() === null) {
				this.selectedFeed.set(list[0]);
			}
		});
		this.gameService.refreshBuildings().subscribe({error: () => undefined});
	}

	ngOnDestroy(): void {
		this.leafletMap?.remove();
	}

	protected setSelectedFeed(value: string | null): void {
		this.selectedFeed.set(value);
	}

	protected toggleBuildMode(kind: BuildingKind): void {
		this.placementError.set(null);
		this.buildMode.update((current) => (current === kind ? null : kind));
		if (this.buildMode() === null) {
			this.pendingPlacement.set(null);
		}
	}

	protected confirmPlacement(): void {
		const kind = this.buildMode();
		const placement = this.pendingPlacement();
		const recipeId = this.selectedRecipeId();
		if (!kind || !placement || !recipeId) {
			return;
		}
		if (!this.isPlacementValid()) {
			this.placementError.set("sidebar.build.errors.INVALID_PLACEMENT_LOCATION");
			return;
		}
		this.placementError.set(null);
		this.gameService
			.placeBuilding({kind, lat: placement.lat, lon: placement.lng, recipeId})
			.subscribe({
				next: () => {
					this.pendingPlacement.set(null);
					this.selectedRecipeId.set(null);
					this.buildMode.set(null);
				},
				error: (error: {error?: {error?: string | null} | null} | null) => {
					const code = error?.error?.error ?? "UNKNOWN_ERROR";
					this.placementError.set(`sidebar.build.errors.${code}`);
				},
			});
	}

	protected cancelPlacement(): void {
		this.pendingPlacement.set(null);
		this.selectedRecipeId.set(null);
		this.placementError.set(null);
	}

	protected closeFactoryPanel(): void {
		this.selectedFactoryId.set(null);
	}

	protected onReorderOperations(newOperations: readonly string[]): void {
		const factoryId = this.selectedFactoryId();
		if (!factoryId) {
			return;
		}
		this.gameService.updateFactoryOperations(factoryId, newOperations).subscribe({error: () => undefined});
	}

	private initialiseMap(): void {
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

		this.leafletMap.on("click", (event) => {
			if (this.buildMode() === null) {
				return;
			}
			this.pendingPlacement.set({lat: event.latlng.lat, lng: event.latlng.lng});
			this.placementError.set(null);
		});

		this.leafletMap.on("mousemove", (event) => {
			const kind = this.buildMode();
			if (kind === null) {
				return;
			}
			this.hoverValid.set(isValidPlacement(kind, event.latlng.lat, event.latlng.lng, this.placementZones()));
		});
	}

	private makeBuildingMarker(building: Building): Leaflet.Marker {
		const isFarm = building.kind === "FARM";
		const iconClass = isFarm ? "pi-leaf" : "pi-cog";
		const cssClass = `building-marker ${isFarm ? "farm" : "factory"}`;
		const icon = Leaflet.divIcon({
			className: cssClass,
			html: `<i class="pi ${iconClass}" aria-hidden="true"></i>`,
			iconSize: [28, 28],
			iconAnchor: [14, 14],
		});
		const recipe = this.recipes().find((candidate) => candidate.id === building.recipeId);
		const recipeName = recipe
			? localize(recipe.displayName, this.languageService.activeLanguage())
			: building.recipeId;
		const tooltipKey = isFarm ? "map.tooltip.farm" : "map.tooltip.factory";
		const tooltipText = `${this.translocoService.translate(tooltipKey)}: ${recipeName}`;
		const marker = Leaflet.marker([building.lat, building.lon], {icon, title: recipeName})
			.bindTooltip(tooltipText, {direction: "top", offset: [0, -8]});
		if (building.kind === "FACTORY") {
			marker.on("click", (event) => {
				Leaflet.DomEvent.stopPropagation(event);
				this.selectedFactoryId.set(building.id);
			});
		}
		return marker;
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
				this.placementLayer = Leaflet.geoJSON(collection as never, {
					style: (feature) => this.styleForFeature(
						(feature?.properties?.["kind"] as PlacementZoneKind) ?? "park",
					),
				}).addTo(map);
				this.loadingZones.set(false);
			},
			error: (error) => {
				console.error("Failed to load placement zones for", feed, error);
				this.loadingZones.set(false);
			},
		});
	}
}

