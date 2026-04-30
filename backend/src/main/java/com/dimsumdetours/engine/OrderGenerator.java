package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import com.dimsumdetours.sim.state.GameState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

		List<Restaurant> eligible = collectEligibleRestaurants();
		if (eligible.isEmpty()) {
			return Optional.empty();
		}

		Restaurant target = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
		Optional<RestaurantTemplate> template = (target.templateId() == null)
			? Optional.empty()
			: registry.findRestaurantTemplate(target.templateId());

		String recipeId = pickRecipe(target, template);
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

	private List<Restaurant> collectEligibleRestaurants() {
		List<Restaurant> eligible = new ArrayList<>();
		for (Building building : gameState.listBuildings()) {
			if (!(building instanceof Restaurant restaurant)) {
				continue;
			}
			int pending = gameState.listOrders(restaurant.id()).size();
			if (pending >= GameConstants.MAX_PENDING_ORDERS_PER_RESTAURANT) {
				continue;
			}
			eligible.add(restaurant);
		}
		return eligible;
	}

	/**
	 * Pick a recipe to demand from the restaurant. Prefers the template's accepted list (so
	 * mods can shape demand variety); falls back to the restaurant's own recipeId for legacy
	 * fixtures with no template.
	 */
	private static String pickRecipe(Restaurant restaurant, Optional<RestaurantTemplate> template) {
		List<String> accepted = template.map(RestaurantTemplate::acceptedRecipeIds).orElse(List.of());
		if (accepted.isEmpty()) {
			return restaurant.recipeId();
		}
		return accepted.get(ThreadLocalRandom.current().nextInt(accepted.size()));
	}
}

