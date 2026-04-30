package com.dimsumdetours.sim.content;

import com.dimsumdetours.sim.model.Ingredient;
import com.dimsumdetours.sim.model.IngredientCategory;
import com.dimsumdetours.sim.model.Operation;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectCollections;

import java.util.Optional;

/**
 * In-memory registry of all loaded game content
 * (ingredients, recipes, operations, ingredient categories, …).
 *
 * <p>Backed by fastutil's {@link Object2ObjectLinkedOpenHashMap} for predictable iteration
 * order (mirrors load order, which mirrors mod-override priority) and lower allocation
 * overhead than {@code java.util.LinkedHashMap}.
 *
 * <p>Framework-agnostic: knows nothing about Spring or the filesystem. A loader in the
 * application layer is responsible for populating it.
 */
public final class ContentRegistry {

	private final Object2ObjectMap<String, Ingredient> ingredients =
		new Object2ObjectLinkedOpenHashMap<>();

	private final Object2ObjectMap<String, Recipe> recipes =
		new Object2ObjectLinkedOpenHashMap<>();

	private final Object2ObjectMap<String, IngredientCategory> categories =
		new Object2ObjectLinkedOpenHashMap<>();

	private final Object2ObjectMap<String, Operation> operations =
		new Object2ObjectLinkedOpenHashMap<>();

	private final Object2ObjectMap<String, RestaurantTemplate> restaurantTemplates =
		new Object2ObjectLinkedOpenHashMap<>();

	// ─── Ingredient categories ───────────────────────────────────────────────

	public void putCategory(IngredientCategory category) {
		categories.put(category.id(), category);
	}

	public Optional<IngredientCategory> findCategory(String categoryId) {
		return Optional.ofNullable(categories.get(categoryId));
	}

	public boolean hasCategory(String categoryId) {
		return categories.containsKey(categoryId);
	}

	public ObjectCollection<IngredientCategory> allCategories() {
		return ObjectCollections.unmodifiable(categories.values());
	}

	public int categoryCount() {
		return categories.size();
	}

	// ─── Operations ──────────────────────────────────────────────────────────

	public void putOperation(Operation operation) {
		operations.put(operation.id(), operation);
	}

	public Optional<Operation> findOperation(String operationId) {
		return Optional.ofNullable(operations.get(operationId));
	}

	public boolean hasOperation(String operationId) {
		return operations.containsKey(operationId);
	}

	public ObjectCollection<Operation> allOperations() {
		return ObjectCollections.unmodifiable(operations.values());
	}

	public int operationCount() {
		return operations.size();
	}

	// ─── Ingredients ─────────────────────────────────────────────────────────

	public void putIngredient(Ingredient ingredient) {
		ingredients.put(ingredient.id(), ingredient);
	}

	public Optional<Ingredient> findIngredient(String ingredientId) {
		return Optional.ofNullable(ingredients.get(ingredientId));
	}

	public boolean hasIngredient(String ingredientId) {
		return ingredients.containsKey(ingredientId);
	}

	public ObjectCollection<Ingredient> allIngredients() {
		return ObjectCollections.unmodifiable(ingredients.values());
	}

	public int ingredientCount() {
		return ingredients.size();
	}

	// ─── Recipes ─────────────────────────────────────────────────────────────

	public void putRecipe(Recipe recipe) {
		recipes.put(recipe.id(), recipe);
	}

	public Optional<Recipe> findRecipe(String recipeId) {
		return Optional.ofNullable(recipes.get(recipeId));
	}

	public ObjectCollection<Recipe> allRecipes() {
		return ObjectCollections.unmodifiable(recipes.values());
	}

	public int recipeCount() {
		return recipes.size();
	}

	// ─── Lifecycle ───────────────────────────────────────────────────────────

	public void clear() {
		categories.clear();
		operations.clear();
		ingredients.clear();
		recipes.clear();
		restaurantTemplates.clear();
	}

	// ─── Restaurant templates ────────────────────────────────────────────────

	public void putRestaurantTemplate(RestaurantTemplate template) {
		restaurantTemplates.put(template.id(), template);
	}

	public Optional<RestaurantTemplate> findRestaurantTemplate(String templateId) {
		return Optional.ofNullable(restaurantTemplates.get(templateId));
	}

	public ObjectCollection<RestaurantTemplate> allRestaurantTemplates() {
		return ObjectCollections.unmodifiable(restaurantTemplates.values());
	}

	public int restaurantTemplateCount() {
		return restaurantTemplates.size();
	}
}
