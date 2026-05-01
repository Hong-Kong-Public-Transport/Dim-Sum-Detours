package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Ingredient;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase-12 dispatcher. On every simulation tick, asks {@link GameState} to:
 * <ol>
 *   <li>spawn a robot for each restaurant order with no in-flight vehicle, and</li>
 *   <li>top up each factory's input stockpile from the nearest producer.</li>
 * </ol>
 * Replaces the old frontend {@code DeliveryService} dispatch logic — the server is now
 * the source of truth for "where every shipment is right now". Frontend just renders.
 *
 * <p>Lives outside {@code sim/} so the framework-agnostic core stays pure (per the
 * README rule); the dispatcher itself is a thin Spring bean over
 * {@link GameState#spawnRobot}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleDispatcher {

	private final GameState gameState;
	private final ContentRegistry registry;

	/**
	 * One pass over restaurant orders + factory stockpiles. Returns the list of spawn
	 * events for the engine to broadcast onto the SSE stream. O(orders × producers)
	 * + O(factories × inputs × producers) — comfortably small in Phase-12 playtests.
	 */
	public List<VehicleEvent.Spawned> dispatch() {
		List<Building> buildings = gameState.listBuildings();
		Map<String, Recipe> recipesById = new HashMap<>();
		for (Recipe recipe : registry.allRecipes()) {
			recipesById.put(recipe.id(), recipe);
		}
		List<VehicleEvent.Spawned> spawned = new ArrayList<>();

		// 1. Restaurant-order dispatch.
		for (Order order : gameState.listAllOrders()) {
			if (gameState.hasInFlightOrder(order.id())) {
				continue;
			}
			Building restaurant = findById(buildings, order.restaurantId());
			if (!(restaurant instanceof Restaurant)) {
				continue;
			}
			Building source = pickNearestSourceForOrder(buildings, recipesById, order, restaurant);
			if (source == null) {
				continue;
			}
			Recipe sourceRecipe = recipesById.get(source.recipeId());
			Long spoilageDeadline = computeSpoilageDeadline(source, sourceRecipe);
			String cargoIngredient = (sourceRecipe != null && !sourceRecipe.outputs().isEmpty())
				? sourceRecipe.outputs().get(0).ingredientId()
				: source.outputIngredientId();
			if (cargoIngredient == null) {
				continue;
			}
			gameState.spawnRobot(
				source.id(),
				restaurant.id(),
				cargoIngredient,
				1,
				order.id(),
				spoilageDeadline
			).ifPresent(spawned::add);
		}

		// 2. Factory-restock dispatch.
		for (Building building : buildings) {
			if (!(building instanceof Factory factory)) {
				continue;
			}
			Recipe recipe = recipesById.get(factory.recipeId());
			if (recipe == null || recipe.inputs().isEmpty()) {
				continue;
			}
			for (RecipeIngredient input : recipe.inputs()) {
				int have = factory.inputStockpile().getOrDefault(input.ingredientId(), 0);
				int desired = input.quantity() * GameConstants.FACTORY_RESTOCK_TARGET_CYCLES;
				if (have >= desired) {
					continue;
				}
				Building producer = nearestProducerOf(buildings, recipesById, factory, input.ingredientId());
				if (producer == null) {
					continue;
				}
				if (gameState.hasActiveRestock(producer.id(), factory.id(), input.ingredientId())) {
					continue;
				}
				gameState.spawnRobot(
					producer.id(),
					factory.id(),
					input.ingredientId(),
					input.quantity(),
					null,
					null
				).ifPresent(spawned::add);
			}
		}

		return spawned;
	}

	private static @Nullable Building findById(List<Building> buildings, java.util.UUID id) {
		for (Building b : buildings) {
			if (b.id().equals(id)) {
				return b;
			}
		}
		return null;
	}

	/**
	 * Pick the nearest farm/factory that can supply the order. Prefers an exact recipe
	 * match; falls back to any upstream producer whose output is consumed (transitively)
	 * by the order's recipe. Source must have {@code producedUnits ≥ 1} to be eligible.
	 */
	private @Nullable Building pickNearestSourceForOrder(
		List<Building> buildings,
		Map<String, Recipe> recipesById,
		Order order,
		Building restaurant
	) {
		Building exact = nearestMatching(buildings, restaurant, candidate -> hasStock(candidate)
			&& candidate.recipeId().equals(order.recipeId()));
		if (exact != null) {
			return exact;
		}
		java.util.Set<String> closure = transitiveInputClosure(order.recipeId(), recipesById);
		if (closure.isEmpty()) {
			return null;
		}
		return nearestMatching(buildings, restaurant, candidate -> {
			if (!hasStock(candidate)) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient out : recipe.outputs()) {
				if (closure.contains(out.ingredientId())) {
					return true;
				}
			}
			return false;
		});
	}

	private @Nullable Building nearestProducerOf(
		List<Building> buildings,
		Map<String, Recipe> recipesById,
		Building destination,
		String ingredientId
	) {
		return nearestMatching(buildings, destination, candidate -> {
			if (!hasStock(candidate) || candidate.id().equals(destination.id())) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient out : recipe.outputs()) {
				if (out.ingredientId().equals(ingredientId)) {
					return true;
				}
			}
			return false;
		});
	}

	private static @Nullable Building nearestMatching(
		List<Building> buildings,
		Building destination,
		java.util.function.Predicate<Building> predicate
	) {
		Building best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Building candidate : buildings) {
			if (candidate instanceof Restaurant) {
				continue;
			}
			if (!predicate.test(candidate)) {
				continue;
			}
			double distance = GameState.haversineMetres(
				candidate.lat(), candidate.lon(), destination.lat(), destination.lon());
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean hasStock(Building candidate) {
		if (candidate instanceof Farm farm) {
			return farm.producedUnits() >= 1;
		}
		if (candidate instanceof Factory factory) {
			return factory.producedUnits() >= 1;
		}
		return false;
	}

	private static java.util.Set<String> transitiveInputClosure(
		String rootRecipeId,
		Map<String, Recipe> recipesById
	) {
		Map<String, List<Recipe>> recipesByOutput = new HashMap<>();
		for (Recipe recipe : recipesById.values()) {
			for (RecipeIngredient out : recipe.outputs()) {
				recipesByOutput.computeIfAbsent(out.ingredientId(), k -> new ArrayList<>()).add(recipe);
			}
		}
		java.util.Set<String> ingredients = new java.util.HashSet<>();
		java.util.Set<String> visitedRecipes = new java.util.HashSet<>();
		java.util.Deque<String> queue = new java.util.ArrayDeque<>();
		queue.add(rootRecipeId);
		while (!queue.isEmpty()) {
			String recipeId = queue.poll();
			if (!visitedRecipes.add(recipeId)) {
				continue;
			}
			Recipe recipe = recipesById.get(recipeId);
			if (recipe == null) {
				continue;
			}
			for (RecipeIngredient input : recipe.inputs()) {
				if (!ingredients.add(input.ingredientId())) {
					continue;
				}
				List<Recipe> producers = recipesByOutput.getOrDefault(input.ingredientId(), List.of());
				for (Recipe producer : producers) {
					queue.add(producer.id());
				}
			}
		}
		return ingredients;
	}

	/**
	 * Spoilage deadline = spawn time + shortest {@code shelfLifeMinutes} across the
	 * source recipe's outputs. Refrigerated factories disable spoilage entirely
	 * (Cold Chain milestone). Returns null for non-perishable cargo.
	 */
	private @Nullable Long computeSpoilageDeadline(Building source, @Nullable Recipe recipe) {
		if (source instanceof Factory factory && factory.refrigerated()) {
			return null;
		}
		if (recipe == null) {
			return null;
		}
		long shortest = Long.MAX_VALUE;
		for (RecipeIngredient out : recipe.outputs()) {
			Optional<Ingredient> ing = registry.findIngredient(out.ingredientId());
			if (ing.isEmpty()) {
				continue;
			}
			long shelf = ing.get().shelfLifeMinutes();
			if (shelf > 0 && shelf < shortest) {
				shortest = shelf;
			}
		}
		if (shortest == Long.MAX_VALUE) {
			return null;
		}
		long now = gameState.getClockSnapshot().gameMinutes();
		return now + shortest;
	}
}

