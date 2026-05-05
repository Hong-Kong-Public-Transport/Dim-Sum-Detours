package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import com.dimsumdetours.sim.state.GameState;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase-7 procedural order generator. Every
 * {@link GameConstants#ORDER_GENERATION_INTERVAL_GAME_MINUTES} game-minutes the
 * simulation engine asks this service to emit one new {@link Order} against a randomly
 * chosen open restaurant — provided the restaurant isn't already saturated past
 * {@link GameConstants#MAX_PENDING_ORDERS_PER_RESTAURANT}.
 *
 * <p>Pulled into its own service (rather than inlined into {@link SimulationEngine}) so
 * the policy can be replaced later — e.g. spawn rates that ramp with city growth, or
 * cuisine-tier-aware demand — without touching the tick loop.
 *
 * <p>Phase-14 perf: the supplyable-recipe-set walk used to be O(R²) per tick due to a
 * nested {@code stream().anyMatch} over {@code registry.allRecipes()}. We now build a
 * static {@code outputIngredient → producing recipes} index once and memoise the
 * supplyable set keyed off {@link GameState#getBuildingsVersion()} so an unchanged
 * map costs O(1) per tick instead of O(R²).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderGenerator {

	private final GameState gameState;
	private final ContentRegistry registry;

	/** Game-minute by which the next emission may fire. Initialised lazily on the first
	 * tick. Marked volatile because {@link #reset()} may be called from a controller
	 * thread while the simulation thread is reading it. */
	private volatile long nextEmissionAtGameMinutes = -1L;

	/** Static index built once on first tick: {@code outputIngredient → recipes producing it}.
	 * Recipe content is immutable post-load, so the index is cached forever. */
	private @Nullable Object2ObjectOpenHashMap<String, ObjectList<Recipe>> recipesByOutputIngredient;

	/** Memoised supplyable-recipe set; valid as long as {@link #cachedVersion} matches
	 * {@link GameState#getBuildingsVersion()}. */
	private @Nullable ObjectSet<String> cachedSupplyable;
	private long cachedVersion = Long.MIN_VALUE;

	/**
	 * Emit at most one new order if the schedule says it's due. Returns the event for the
	 * engine to broadcast onto the SSE stream. Empty if no restaurant is eligible or the
	 * schedule hasn't elapsed yet.
	 */
	public Optional<OrderEvent.Enqueued> maybeGenerate(long currentGameMinutes) {
		if (nextEmissionAtGameMinutes < 0L) {
			nextEmissionAtGameMinutes = currentGameMinutes + GameConstants.ORDER_GENERATION_INTERVAL_GAME_MINUTES;
			return Optional.empty();
		}
		if (currentGameMinutes < nextEmissionAtGameMinutes) {
			return Optional.empty();
		}
		// Schedule the next emission off the *threshold* (not the current minute) so high
		// game-speeds don't drift the cadence: a 256× tick that overshoots by 200 minutes
		// still emits exactly one order.
		nextEmissionAtGameMinutes += GameConstants.ORDER_GENERATION_INTERVAL_GAME_MINUTES;

		// Phase-13 fix: compute the supplyable-recipe set ONCE per tick and use it both
		// to filter the eligible restaurants AND to pick a recipe. The previous build
		// random-picked an eligible restaurant first and only then checked whether any of
		// its accepted recipes was supplyable — so when the player had set up a chain for
		// only some of the auto-spawned restaurants, the random pick landed on a
		// non-supplyable restaurant most of the time and the whole tick was skipped.
		ObjectSet<String> supplyable = supplyableRecipeIds();
		ObjectList<Restaurant> eligible = collectEligibleRestaurants(supplyable);
		if (eligible.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("Order tick skipped: 0 eligible restaurants. supplyableRecipes={}", supplyable);
			}
			return Optional.empty();
		}

		Restaurant target = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
		Optional<RestaurantTemplate> template = (target.templateId() == null)
			? Optional.empty()
			: registry.findRestaurantTemplate(target.templateId());

		String recipeId = pickRecipe(target, template, supplyable);
		if (recipeId == null) {
			log.debug("Procedural order skipped — no supplyable recipe for restaurant {}", target.id());
			return Optional.empty();
		}
		long patience = template.map(RestaurantTemplate::basePatienceMinutes)
			.orElse(GameConstants.ORDER_GENERATION_INTERVAL_GAME_MINUTES * 4L);

		Optional<Order> created = gameState.enqueueOrder(target.id(), recipeId, 1, patience);
		if (created.isEmpty()) {
			log.debug("Procedural order skipped — restaurant {} no longer present", target.id());
			return Optional.empty();
		}
		return Optional.of(new OrderEvent.Enqueued(created.get(), currentGameMinutes));
	}

	/** Resets the schedule. Called by the engine on game reset. */
	public void reset() {
		nextEmissionAtGameMinutes = -1L;
		// The cached supplyable set is keyed off the GameState version counter, which
		// reset() also bumps, so the cache will self-invalidate on next access. We
		// don't need to null it here.
	}

	private ObjectList<Restaurant> collectEligibleRestaurants(ObjectSet<String> supplyable) {
		ObjectList<Restaurant> eligible = new ObjectArrayList<>();
		for (Building building : gameState.listBuildings()) {
			if (!(building instanceof Restaurant restaurant)) {
				continue;
			}
			if (restaurant.closed()) {
				continue; // closed restaurants don't generate new demand
			}
			int pending = gameState.listOrders(restaurant.id()).size();
			if (pending >= GameConstants.MAX_PENDING_ORDERS_PER_RESTAURANT) {
				continue;
			}
			if (!hasSupplyableAcceptedRecipe(restaurant, supplyable)) {
				continue; // no point picking a restaurant whose chain isn't built yet
			}
			eligible.add(restaurant);
		}
		return eligible;
	}

	/** True iff at least one of the restaurant's accepted recipes (or its house dish, when
	 * the template lookup misses) is in the supplyable set. */
	private boolean hasSupplyableAcceptedRecipe(Restaurant restaurant, ObjectSet<String> supplyable) {
		Optional<RestaurantTemplate> template = (restaurant.templateId() == null)
			? Optional.empty()
			: registry.findRestaurantTemplate(restaurant.templateId());
		List<String> accepted = template.map(RestaurantTemplate::acceptedRecipeIds).orElse(List.of());
		if (!accepted.isEmpty()) {
			for (String id : accepted) {
				if (supplyable.contains(id)) {
					return true;
				}
			}
			return false;
		}
		return supplyable.contains(restaurant.recipeId());
	}

	/**
	 * Pick a recipe to demand from the restaurant. Prefers any accepted recipe that is
	 * actually supplyable from the placed buildings (Phase-9 fix); falls back to the full
	 * accepted list if nothing supplyable, and finally to the restaurant's own house dish.
	 * Returns {@code null} when the accepted list is empty AND the house-dish chain isn't
	 * supplyable — the caller skips the tick rather than parking an orphan order.
	 */
	private static @Nullable String pickRecipe(
		Restaurant restaurant,
		Optional<RestaurantTemplate> template,
		ObjectSet<String> supplyable
	) {
		List<String> accepted = template.map(RestaurantTemplate::acceptedRecipeIds).orElse(List.of());
		if (!accepted.isEmpty()) {
			ObjectList<String> filtered = new ObjectArrayList<>();
			for (String id : accepted) {
				if (supplyable.contains(id)) {
					filtered.add(id);
				}
			}
			if (!filtered.isEmpty()) {
				return filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
			}
			return null;
		}
		return supplyable.contains(restaurant.recipeId()) ? restaurant.recipeId() : null;
	}

	/**
	 * Memoised: every recipe whose entire ingredient chain can be sourced from
	 * currently-placed farms / factories. Re-computed only when
	 * {@link GameState#getBuildingsVersion()} advances; an unchanged map returns the
	 * cached set without touching {@link ContentRegistry#allRecipes()} at all.
	 */
	private ObjectSet<String> supplyableRecipeIds() {
		long currentVersion = gameState.getBuildingsVersion();
		ObjectSet<String> cached = cachedSupplyable;
		if (cached != null && currentVersion == cachedVersion) {
			return cached;
		}
		ObjectSet<String> recomputed = computeSupplyableRecipeIds();
		cachedSupplyable = recomputed;
		cachedVersion = currentVersion;
		return recomputed;
	}

	/**
	 * Phase-9: compute the set of recipe ids whose entire ingredient chain can be sourced
	 * from currently-placed farms / factories. A recipe is "supplyable" when every direct
	 * input ingredient has at least one placed producer (transitively closed through
	 * factory recipes that produce that ingredient as an output). This keeps the generator
	 * from spawning orders for {@code cha_siu_bao} when the player only owns a garlic farm.
	 */
	private ObjectSet<String> computeSupplyableRecipeIds() {
		List<Building> placed = gameState.listBuildings();

		// Step 1: which ingredient ids does ANY placed farm/factory currently produce?
		ObjectSet<String> producedIngredients = new ObjectOpenHashSet<>();
		ObjectSet<String> placedRecipeIds = new ObjectOpenHashSet<>();
		for (Building building : placed) {
			if (building instanceof Farm farm) {
				producedIngredients.add(farm.outputIngredientId());
				placedRecipeIds.add(farm.recipeId());
			} else if (building instanceof Factory factory) {
				placedRecipeIds.add(factory.recipeId());
				registry.findRecipe(factory.recipeId()).ifPresent(recipe -> {
					for (RecipeIngredient output : recipe.outputs()) {
						producedIngredients.add(output.ingredientId());
					}
				});
			}
		}

		// Step 2: a recipe is "directly supplyable" if every input ingredient is in
		// producedIngredients (or via some other supplyable recipe). Walk every known
		// recipe and tag the ones that pass.
		Object2ObjectOpenHashMap<String, ObjectList<Recipe>> byOutput = ensureRecipesByOutput();
		ObjectSet<String> supplyable = new ObjectOpenHashSet<>();
		ObjectSet<String> visitedScratch = new ObjectOpenHashSet<>();
		for (Recipe recipe : registry.allRecipes()) {
			visitedScratch.clear();
			if (isRecipeChainSupplyable(recipe, producedIngredients, placedRecipeIds, byOutput, visitedScratch)) {
				supplyable.add(recipe.id());
			}
		}
		return supplyable;
	}

	private Object2ObjectOpenHashMap<String, ObjectList<Recipe>> ensureRecipesByOutput() {
		Object2ObjectOpenHashMap<String, ObjectList<Recipe>> byOutput = recipesByOutputIngredient;
		if (byOutput != null) {
			return byOutput;
		}
		byOutput = new Object2ObjectOpenHashMap<>();
		for (Recipe recipe : registry.allRecipes()) {
			for (RecipeIngredient output : recipe.outputs()) {
				byOutput.computeIfAbsent(output.ingredientId(), id -> new ObjectArrayList<>()).add(recipe);
			}
		}
		recipesByOutputIngredient = byOutput;
		return byOutput;
	}

	/**
	 * Recursive supply-chain check. {@code visited} guards cycles. The previous build
	 * iterated {@code registry.allRecipes()} inside this method; the Phase-14 rewrite
	 * uses the pre-built {@code byOutput} index so each input ingredient probes only
	 * the recipes that actually produce it.
	 */
	private boolean isRecipeChainSupplyable(
		Recipe recipe,
		ObjectSet<String> producedIngredients,
		ObjectSet<String> placedRecipeIds,
		Object2ObjectOpenHashMap<String, ObjectList<Recipe>> byOutput,
		ObjectSet<String> visited
	) {
		if (!visited.add(recipe.id())) {
			return false;
		}
		// Phase-10 bug-fix: EVERY recipe — farm or factory — must have a placed producer
		// to count as supplyable. Without this guard, factory dishes (e.g. cha_siu_bao)
		// were considered supplyable as soon as their input ingredients existed somewhere
		// on the map, generating orders the dispatcher couldn't fulfil and silently
		// docking reputation.
		if (!placedRecipeIds.contains(recipe.id())) {
			return false;
		}
		if (recipe.inputs().isEmpty()) {
			return true;
		}
		for (RecipeIngredient input : recipe.inputs()) {
			if (producedIngredients.contains(input.ingredientId())) {
				continue;
			}
			ObjectList<Recipe> producers = byOutput.get(input.ingredientId());
			if (producers == null) {
				return false;
			}
			boolean satisfied = false;
			for (Recipe candidate : producers) {
				ObjectSet<String> branchVisited = new ObjectOpenHashSet<>(visited);
				if (isRecipeChainSupplyable(candidate, producedIngredients, placedRecipeIds, byOutput, branchVisited)) {
					satisfied = true;
					break;
				}
			}
			if (!satisfied) {
				return false;
			}
		}
		return true;
	}
}

