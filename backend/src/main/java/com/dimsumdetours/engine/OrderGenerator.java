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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderGenerator {

	private final GameState gameState;
	private final ContentRegistry registry;

	/** Game-minute by which the next emission may fire. Initialised lazily on the first tick. */
	private long nextEmissionAtGameMinutes = -1L;

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
		// non-supplyable restaurant most of the time and the whole tick was skipped. Net
		// effect to the player: "I built a full supply chain but orders never appear."
		Set<String> supplyable = computeSupplyableRecipeIds();
		List<Restaurant> eligible = collectEligibleRestaurants(supplyable);
		if (eligible.isEmpty()) {
			// Phase-13: surface the reason so players who placed "what they thought was a
			// full chain" can see WHY the generator is skipping. The most common miss is
			// not realising they need to place a factory matching a recipe id the
			// restaurant accepts (the chain-supplyability check enforces this).
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
			// Shouldn't happen now that collectEligibleRestaurants filters on supplyability,
			// but kept as a defensive guard so a registry / state race never crashes the tick.
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
	}

	private List<Restaurant> collectEligibleRestaurants(Set<String> supplyable) {
		List<Restaurant> eligible = new ArrayList<>();
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
	private boolean hasSupplyableAcceptedRecipe(Restaurant restaurant, Set<String> supplyable) {
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
	private static String pickRecipe(
		Restaurant restaurant,
		Optional<RestaurantTemplate> template,
		Set<String> supplyable
	) {
		List<String> accepted = template.map(RestaurantTemplate::acceptedRecipeIds).orElse(List.of());
		if (!accepted.isEmpty()) {
			List<String> filtered = new ArrayList<>();
			for (String id : accepted) {
				if (supplyable.contains(id)) {
					filtered.add(id);
				}
			}
			if (!filtered.isEmpty()) {
				return filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
			}
			// No supplyable accepted recipe — emit nothing rather than spawn an orphan order.
			return null;
		}
		return supplyable.contains(restaurant.recipeId()) ? restaurant.recipeId() : null;
	}

	/**
	 * Phase-9: compute the set of recipe ids whose entire ingredient chain can be sourced
	 * from currently-placed farms / factories. A recipe is "supplyable" when every direct
	 * input ingredient has at least one placed producer (transitively closed through
	 * factory recipes that produce that ingredient as an output). This keeps the generator
	 * from spawning orders for {@code cha_siu_bao} when the player only owns a garlic farm.
	 */
	private Set<String> computeSupplyableRecipeIds() {
		List<Building> placed = gameState.listBuildings();

		// Step 1: which ingredient ids does ANY placed farm/factory currently produce?
		Set<String> producedIngredients = new HashSet<>();
		Set<String> placedRecipeIds = new HashSet<>();
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
		// producedIngredients. Walk every known recipe and tag the ones that pass.
		Set<String> supplyable = new HashSet<>();
		for (Recipe recipe : registry.allRecipes()) {
			if (isRecipeChainSupplyable(recipe, producedIngredients, placedRecipeIds, new HashSet<>())) {
				supplyable.add(recipe.id());
			}
		}
		return supplyable;
	}

	/**
	 * Recursive supply-chain check: a recipe is supplyable if every input ingredient is
	 * either directly produced (by a placed farm/factory) or supplyable via some other
	 * recipe in the registry whose own chain is supplyable. {@code visited} guards cycles.
	 */
	private boolean isRecipeChainSupplyable(
		Recipe recipe,
		Set<String> producedIngredients,
		Set<String> placedRecipeIds,
		Set<String> visited
	) {
		if (!visited.add(recipe.id())) {
			return false; // cycle — bail out on the conservative side
		}
		// Phase-10 bug-fix: EVERY recipe — farm or factory — must have a placed producer to
		// count as supplyable. The previous build only enforced this for no-input recipes,
		// which let factory dishes (e.g. cha_siu_bao) be considered supplyable as soon as
		// their input ingredients existed somewhere on the map — even if the player had
		// never placed a cha_siu_bao factory. The generator then enqueued orders the
		// dispatcher couldn't fulfil; they expired silently and docked reputation, which
		// players noticed as "reputation falls with no orders visible".
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
			boolean satisfied = false;
			for (Recipe candidate : registry.allRecipes()) {
				boolean producesInput = candidate.outputs().stream()
					.anyMatch(out -> out.ingredientId().equals(input.ingredientId()));
				if (!producesInput) {
					continue;
				}
				if (isRecipeChainSupplyable(candidate, producedIngredients, placedRecipeIds, new HashSet<>(visited))) {
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

