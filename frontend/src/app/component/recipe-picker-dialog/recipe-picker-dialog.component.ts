import {ChangeDetectionStrategy, Component, computed, inject, input, output, signal} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {DialogModule} from "primeng/dialog";

import {localize} from "../../core/i18n/localize";
import {BuildingKind} from "../../core/model/building.model";
import {Ingredient} from "../../core/model/ingredient.model";
import {Operation, Recipe} from "../../core/model/recipe.model";
import {RestaurantTemplate} from "../../core/model/restaurant-template.model";
import {LanguageService} from "../../core/service/language.service";
import {RecipeTileComponent} from "../recipe-tile/recipe-tile.component";
import {SearchBoxComponent} from "../search-box/search-box.component";

/** The placeable subset — every kind the player builds by hand. */
type PlaceableBuildingKind = Extract<BuildingKind, "FARM" | "FACTORY" | "RESTAURANT">;

/**
 * Modal recipe-picker shown when the player clicks "Place farm" / "Place factory" /
 * "Place restaurant". Pure presentation: receives the kind + lookup tables, emits
 * {@code picked} (recipe id) or {@code cancelled}. The parent owns the placement flow.
 *
 * <p>For restaurants the choices are derived from the union of every
 * {@link RestaurantTemplate#acceptedRecipeIds} so we don't surface intermediate / farm-only
 * recipes that no restaurant would actually accept.
 */
@Component({
	selector: "app-recipe-picker-dialog",
	imports: [ButtonModule, DialogModule, RecipeTileComponent, SearchBoxComponent, TranslocoDirective],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./recipe-picker-dialog.component.html",
	styleUrl: "./recipe-picker-dialog.component.scss",
})
export class RecipePickerDialogComponent {
	private readonly languageService = inject(LanguageService);

	readonly kind = input<PlaceableBuildingKind | null>(null);
	readonly recipes = input.required<readonly Recipe[]>();
	readonly ingredients = input.required<readonly Ingredient[]>();
	readonly operations = input<readonly Operation[]>([]);
	readonly restaurantTemplates = input<readonly RestaurantTemplate[]>([]);

	readonly picked = output<string>();
	readonly cancelled = output<void>();

	protected readonly visible = computed(() => this.kind() !== null);
	protected readonly filter = signal<string>("");

	protected readonly choices = computed<readonly Recipe[]>(() => {
		const kind = this.kind();
		if (!kind) {
			return [];
		}
		const isFarmRecipe = (recipe: Recipe): boolean => recipe.tags.includes("farm");

		if (kind === "RESTAURANT") {
			const acceptedIds = new Set<string>();
			for (const template of this.restaurantTemplates()) {
				for (const recipeId of template.acceptedRecipeIds) {
					acceptedIds.add(recipeId);
				}
			}
			return this.recipes().filter((recipe) => acceptedIds.has(recipe.id));
		}

		const matchesKind = (recipe: Recipe): boolean =>
			kind === "FARM" ? isFarmRecipe(recipe) : !isFarmRecipe(recipe);

		return this.recipes()
			.filter((recipe) => recipe.minimumFactoryTier === 1)
			.filter(matchesKind);
	});

	/** Choices narrowed by the in-dialog search box (matches localised name + id). */
	protected readonly visibleChoices = computed<readonly Recipe[]>(() => {
		const needle = this.filter().trim().toLowerCase();
		if (!needle) {
			return this.choices();
		}
		const language = this.languageService.activeLanguage();
		return this.choices().filter((recipe) => {
			const name = localize(recipe.displayName, language).toLowerCase();
			return name.includes(needle) || recipe.id.toLowerCase().includes(needle);
		});
	});

	protected onVisibleChange(open: boolean): void {
		if (!open) {
			this.filter.set("");
			this.cancelled.emit();
		}
	}

	protected pick(recipeId: string): void {
		this.picked.emit(recipeId);
	}
}

