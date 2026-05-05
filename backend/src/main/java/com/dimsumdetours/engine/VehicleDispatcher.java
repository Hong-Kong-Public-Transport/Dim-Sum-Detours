package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Ingredient;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.MultiLegPlanner;
import com.dimsumdetours.sim.state.VehicleChain;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

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
 *
 * <p>Phase-14 perf: recipe-graph derivatives ({@code recipesById},
 * {@code recipesByOutput}, transitive-input closures) are immutable for the lifetime
 * of the {@link ContentRegistry} and are cached lazily on first dispatch instead of
 * being rebuilt every tick. Building lookup is also pre-indexed into a
 * {@code UUID → Building} map per-tick to drop the per-order O(n) {@code findById} scan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleDispatcher {

	private final GameState gameState;
	private final ContentRegistry registry;
	private final MultiLegPlanner multiLegPlanner;

	/** Recipe lookup by id. Eager-built in {@link #buildRecipeIndices()} after Spring
	 * has finished wiring the {@link ContentRegistry}. Immutable post-load. */
	private final Object2ObjectOpenHashMap<String, Recipe> recipesById = new Object2ObjectOpenHashMap<>();
	/** {@code outputIngredientId → producing recipes}. Eager-built. Immutable post-load. */
	private final Object2ObjectOpenHashMap<String, ObjectList<Recipe>> recipesByOutput = new Object2ObjectOpenHashMap<>();
	/** Memoised: {@code recipeId → set of every ingredient consumed transitively}. */
	private final Object2ObjectOpenHashMap<String, ObjectSet<String>> transitiveInputClosureByRecipe =
		new Object2ObjectOpenHashMap<>();

	@PostConstruct
	void buildRecipeIndices() {
		for (Recipe recipe : registry.allRecipes()) {
			recipesById.put(recipe.id(), recipe);
			for (RecipeIngredient output : recipe.outputs()) {
				recipesByOutput.computeIfAbsent(output.ingredientId(), id -> new ObjectArrayList<>()).add(recipe);
			}
		}
	}

	/**
	 * One pass over restaurant orders + factory stockpiles. Returns the list of spawn
	 * events for the engine to broadcast onto the SSE stream. With the Phase-14 caches
	 * the per-tick cost is O(orders × producers) + O(factories × inputs × producers),
	 * with each factor walking a small {@link ObjectArrayList} rather than allocating
	 * any per-tick HashMaps.
	 */
	// fastutil's get() returns null on miss, but its type isn't @NullMarked so IntelliJ
	// reads it as @NonNull — keep the defensive null guards for content-hot-reload safety.
	@SuppressWarnings("ConstantValue")
	public List<VehicleEvent.Spawned> dispatch() {
		List<Building> buildings = gameState.listBuildings();
		ObjectList<VehicleEvent.Spawned> spawned = new ObjectArrayList<>();

		// 1. Restaurant-order dispatch.
		for (Order order : gameState.listAllOrders()) {
			if (gameState.hasInFlightOrder(order.id())) {
				continue;
			}
			Building restaurant = findById(buildings, order.restaurantId());
			if (!(restaurant instanceof Restaurant)) {
				continue;
			}
			Building source = pickNearestSourceForOrder(buildings, order, restaurant, false);
			if (source == null) {
				continue;
			}
			Recipe sourceRecipe = recipesById.get(source.recipeId());
			Long spoilageDeadline = computeSpoilageDeadline(source, sourceRecipe);
			String cargoIngredient = (sourceRecipe != null && !sourceRecipe.outputs().isEmpty())
				? sourceRecipe.outputs().getFirst().ingredientId()
				: source.outputIngredientId();
			if (cargoIngredient == null) {
				continue;
			}
			gameState.spawnRobot(
				source.id(),
				restaurant.id(),
				cargoIngredient,
				GameConstants.ROBOT_CARGO_BATCH_SIZE,
				order.id(),
				spoilageDeadline
			).ifPresent(spawned::add);
		}

		// 1b. Restaurant-order long-haul fallback: when no producer sits within
		// MAX_ROBOT_LEG_METERS, ask the multi-leg planner for a robot→bus→robot
		// chain. If no GTFS-aware planner is wired (or no chain is available
		// right now), the order simply waits another tick.
		for (Order order : gameState.listAllOrders()) {
			if (gameState.hasInFlightOrder(order.id())) {
				continue;
			}
			Building restaurant = findById(buildings, order.restaurantId());
			if (!(restaurant instanceof Restaurant)) {
				continue;
			}
			// Skip if a short-leg pick already grabbed this order in pass 1.
			if (gameState.hasInFlightOrder(order.id())) {
				continue;
			}
			Building source = pickNearestSourceForOrder(buildings, order, restaurant, true);
			if (source == null) {
				continue;
			}
			double sourceDistance = GameState.haversineMetres(
				source.lat(), source.lon(), restaurant.lat(), restaurant.lon());
			if (sourceDistance <= GameConstants.MAX_ROBOT_LEG_METERS) {
				continue; // already attempted above
			}
			Recipe sourceRecipe = recipesById.get(source.recipeId());
			Long spoilageDeadline = computeSpoilageDeadline(source, sourceRecipe);
			String cargoIngredient = (sourceRecipe != null && !sourceRecipe.outputs().isEmpty())
				? sourceRecipe.outputs().getFirst().ingredientId()
				: source.outputIngredientId();
			if (cargoIngredient == null) {
				continue;
			}
			Optional<VehicleChain> chain = multiLegPlanner.plan(
				new LatLon(source.lat(), source.lon()),
				new LatLon(restaurant.lat(), restaurant.lon()),
				gameState.getClockSnapshot().gameMinutes());
			if (chain.isEmpty()) {
				continue;
			}
			gameState.spawnPlannedFirstLeg(
				source.id(),
				restaurant.id(),
				cargoIngredient,
				GameConstants.ROBOT_CARGO_BATCH_SIZE,
				chain.get().firstLegPath(),
				chain.get().firstLegDurationGameMinutes(),
				order.id(),
				spoilageDeadline,
				chain.get().firstLegHandoff()
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
				// Phase-17: only top up when the gap is at least one full robot batch.
				// Otherwise we'd dispatch a 5-unit batch to fill a 1-unit hole and waste 4.
				if (desired - have < GameConstants.ROBOT_CARGO_BATCH_SIZE) {
					continue;
				}
				Building producer = nearestProducerOf(buildings, factory, input.ingredientId());
				if (producer == null) {
					continue;
				}
				if (gameState.hasActiveRestock(producer.id(), factory.id(), input.ingredientId())) {
					continue;
				}
				double producerDistance = GameState.haversineMetres(
					producer.lat(), producer.lon(), factory.lat(), factory.lon());
				if (producerDistance > GameConstants.MAX_ROBOT_LEG_METERS) {
					Optional<VehicleChain> chain = multiLegPlanner.plan(
						new LatLon(producer.lat(), producer.lon()),
						new LatLon(factory.lat(), factory.lon()),
						gameState.getClockSnapshot().gameMinutes());
					if (chain.isEmpty()) {
						continue;
					}
					gameState.spawnPlannedFirstLeg(
						producer.id(),
						factory.id(),
						input.ingredientId(),
						GameConstants.ROBOT_CARGO_BATCH_SIZE,
						chain.get().firstLegPath(),
						chain.get().firstLegDurationGameMinutes(),
						null,
						null,
						chain.get().firstLegHandoff()
					).ifPresent(spawned::add);
					continue;
				}
				gameState.spawnRobot(
					producer.id(),
					factory.id(),
					input.ingredientId(),
					GameConstants.ROBOT_CARGO_BATCH_SIZE,
					null,
					null
				).ifPresent(spawned::add);
			}
		}

		return spawned;
	}

	private static @Nullable Building findById(List<Building> buildings, UUID id) {
		for (Building building : buildings) {
			if (building.id().equals(id)) {
				return building;
			}
		}
		return null;
	}

	/**
	 * Pick the nearest farm/factory that can supply the order. Prefers an exact recipe
	 * match; falls back to any upstream producer whose output is consumed (transitively)
	 * by the order's recipe. Source must have {@code producedUnits ≥ 1} to be eligible.
	 *
	 * @param allowLongHaul when true, candidates beyond
	 *     {@link GameConstants#MAX_ROBOT_LEG_METERS} are considered (for the
	 *     multi-leg planner pass); when false, only short-leg candidates are
	 *     considered (for the direct robot pass).
	 */
	@SuppressWarnings("ConstantValue") // fastutil get() may return null; see dispatch().
	private @Nullable Building pickNearestSourceForOrder(
		List<Building> buildings,
		Order order,
		Building restaurant,
		boolean allowLongHaul
	) {
		Building exact = nearestMatching(buildings, restaurant, allowLongHaul,
			candidate -> hasStock(candidate)
				&& candidate.recipeId().equals(order.recipeId()));
		if (exact != null) {
			return exact;
		}
		ObjectSet<String> closure = transitiveInputClosure(order.recipeId());
		if (closure.isEmpty()) {
			return null;
		}
		return nearestMatching(buildings, restaurant, allowLongHaul, candidate -> {
			if (!hasStock(candidate)) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient output : recipe.outputs()) {
				if (closure.contains(output.ingredientId())) {
					return true;
				}
			}
			return false;
		});
	}

	@SuppressWarnings("ConstantValue") // fastutil get() may return null; see dispatch().
	private @Nullable Building nearestProducerOf(
		List<Building> buildings,
		Building destination,
		String ingredientId
	) {
		return nearestMatching(buildings, destination, true, candidate -> {
			if (!hasStock(candidate) || candidate.id().equals(destination.id())) {
				return false;
			}
			Recipe recipe = recipesById.get(candidate.recipeId());
			if (recipe == null) {
				return false;
			}
			for (RecipeIngredient output : recipe.outputs()) {
				if (output.ingredientId().equals(ingredientId)) {
					return true;
				}
			}
			return false;
		});
	}

	private static @Nullable Building nearestMatching(
		List<Building> buildings,
		Building destination,
		boolean allowLongHaul,
		Predicate<Building> predicate
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
			// Phase-16: only enforce the single-leg cap when the caller is in the
			// short-leg dispatch pass. The long-haul pass passes allowLongHaul=true
			// so the multi-leg planner gets a chance to bridge the gap.
			if (!allowLongHaul && distance > GameConstants.MAX_ROBOT_LEG_METERS) {
				continue;
			}
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean hasStock(Building candidate) {
		// Phase-17: a candidate is only eligible if it can fill a full robot batch.
		// One-off units stay on the producer until enough accumulate to ship a truck.
		if (candidate instanceof Farm farm) {
			return farm.producedUnits() >= GameConstants.ROBOT_CARGO_BATCH_SIZE;
		}
		if (candidate instanceof Factory factory) {
			return factory.producedUnits() >= GameConstants.ROBOT_CARGO_BATCH_SIZE;
		}
		return false;
	}

	/**
	 * Memoised: every ingredient id transitively consumed when producing
	 * {@code rootRecipeId}. Walks the recipe graph breadth-first through producing
	 * recipes (pulled from the cached {@link #recipesByOutput}). Recipe content is
	 * immutable post-load so the result is cached forever.
	 */
	@SuppressWarnings("ConstantValue") // fastutil get() may return null.
	private ObjectSet<String> transitiveInputClosure(String rootRecipeId) {
		ObjectSet<String> cached = transitiveInputClosureByRecipe.get(rootRecipeId);
		if (cached != null) {
			return cached;
		}
		ObjectSet<String> ingredients = new ObjectOpenHashSet<>();
		ObjectSet<String> visitedRecipes = new ObjectOpenHashSet<>();
		ObjectList<String> queue = new ObjectArrayList<>();
		queue.add(rootRecipeId);
		while (!queue.isEmpty()) {
			String recipeId = queue.removeLast();
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
				ObjectList<Recipe> producers = recipesByOutput.get(input.ingredientId());
				if (producers == null) {
					continue;
				}
				for (Recipe producer : producers) {
					queue.add(producer.id());
				}
			}
		}
		transitiveInputClosureByRecipe.put(rootRecipeId, ingredients);
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
		for (RecipeIngredient output : recipe.outputs()) {
			Optional<Ingredient> ingredient = registry.findIngredient(output.ingredientId());
			if (ingredient.isEmpty()) {
				continue;
			}
			long shelf = ingredient.get().shelfLifeMinutes();
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

