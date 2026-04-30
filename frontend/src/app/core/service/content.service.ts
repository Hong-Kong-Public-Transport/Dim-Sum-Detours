import {HttpClient} from "@angular/common/http";
import {inject, Injectable} from "@angular/core";
import {Observable} from "rxjs";

import {Ingredient, IngredientCategory} from "../model/ingredient.model";
import {Operation, Recipe} from "../model/recipe.model";
import {RestaurantTemplate} from "../model/restaurant-template.model";

@Injectable({providedIn: "root"})
export class ContentService {
	private readonly httpClient = inject(HttpClient);

	listCategories(): Observable<IngredientCategory[]> {
		return this.httpClient.get<IngredientCategory[]>("/api/content/categories");
	}

	listOperations(): Observable<Operation[]> {
		return this.httpClient.get<Operation[]>("/api/content/operations");
	}

	listIngredients(): Observable<Ingredient[]> {
		return this.httpClient.get<Ingredient[]>("/api/content/ingredients");
	}

	getIngredient(ingredientId: string): Observable<Ingredient> {
		return this.httpClient.get<Ingredient>(
			`/api/content/ingredients/${encodeURIComponent(ingredientId)}`,
		);
	}

	listRecipes(): Observable<Recipe[]> {
		return this.httpClient.get<Recipe[]>("/api/content/recipes");
	}

	getRecipe(recipeId: string): Observable<Recipe> {
		return this.httpClient.get<Recipe>(
			`/api/content/recipes/${encodeURIComponent(recipeId)}`,
		);
	}

	listRestaurantTemplates(): Observable<RestaurantTemplate[]> {
		return this.httpClient.get<RestaurantTemplate[]>("/api/content/restaurants");
	}
}
