import {AfterViewInit, ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, OnDestroy, signal, viewChild} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {CardModule} from "primeng/card";
import * as Leaflet from "leaflet";

import {GAME_CONSTANTS} from "../../core/constants/game.constants";
import {ContentService} from "../../core/services/content.service";
import {GtfsService} from "../../core/services/gtfs.service";
import {LanguageService} from "../../core/services/language.service";
import {ThemeService} from "../../core/services/theme.service";
import {Ingredient} from "../../core/models/ingredient.model";
import {Recipe} from "../../core/models/recipe.model";
import {localize} from "../../core/i18n/localize";

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

@Component({
	selector: "app-map",
	imports: [TranslocoDirective, CardModule],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./map.component.html",
	styleUrl: "./map.component.scss",
})
export class MapComponent implements AfterViewInit, OnDestroy {
	private readonly mapElement = viewChild.required<ElementRef<HTMLDivElement>>("mapElement");
	private readonly contentService = inject(ContentService);
	private readonly gtfsService = inject(GtfsService);
	private readonly languageService = inject(LanguageService);
	private readonly themeService = inject(ThemeService);

	protected readonly ingredients = signal<readonly Ingredient[]>([]);
	protected readonly recipes = signal<readonly Recipe[]>([]);
	protected readonly feeds = signal<readonly string[]>([]);

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
			inputs: recipe.inputs.map((line) => formatLine(line.ingredientId, line.quantity)).join(" + "),
			outputs: recipe.outputs.map((line) => formatLine(line.ingredientId, line.quantity)).join(" + "),
			operations: recipe.operations.join(" → "),
		}));
	});

	private leafletMap?: Leaflet.Map;
	private tileLayer?: Leaflet.TileLayer;

	constructor() {
		// Re-skin the Leaflet tile layer whenever the theme flips. Effect is registered in
		// the injection context; runs only after the map exists.
		effect(() => {
			const tileUrl = this.themeService.mapTileUrl();
			if (this.tileLayer) {
				this.tileLayer.setUrl(tileUrl);
			}
		});
	}

	ngAfterViewInit(): void {
		this.initialiseMap();
		this.contentService.listIngredients().subscribe((list) => this.ingredients.set(list));
		this.contentService.listRecipes().subscribe((list) => this.recipes.set(list));
		this.gtfsService.listFeeds().subscribe((list) => this.feeds.set(list));
	}

	ngOnDestroy(): void {
		this.leafletMap?.remove();
	}

	private initialiseMap(): void {
		const {fallbackCenter, defaultZoom, minZoom, maxZoom, tileAttribution} =
			GAME_CONSTANTS.map;

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
	}
}
