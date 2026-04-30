package com.dimsumdetours.sim.state;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderResult;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-6 unit tests for the order lifecycle: enqueue → fulfill (on time + late), expire, plus
 * payout / reputation deltas. Avoids Spring entirely — operates on a plain {@link GameState}
 * with a hand-built {@link ContentRegistry} fixture.
 */
class GameStateOrdersTest {

	private static final String RECIPE_ID = "garlic_rice";
	private static final String TEMPLATE_ID = "test_diner";
	private static final long BASE_PAYOUT = 1_000L;

	private GameState newState() {
		ContentRegistry registry = new ContentRegistry();
		registry.putRecipe(new Recipe(
			RECIPE_ID,
			Map.of("en", "Garlic rice"),
			List.of(),
			List.of("cook"),
			List.of(new RecipeIngredient(RECIPE_ID, 1)),
			1,
			10,
			List.of()));
		registry.putRestaurantTemplate(new RestaurantTemplate(
			TEMPLATE_ID,
			Map.of("en", "Test Diner"),
			List.of(RECIPE_ID),
			120,
			BASE_PAYOUT,
			List.of()));
		GameState state = new GameState(registry, Money.of(10_000L));
		// The clock now boots paused; tests advance the clock manually so unpause to make
		// {@code advanceClock(...)} take effect.
		state.setClockPaused(false);
		return state;
	}

	@Test
	void fulfilledOnTime_creditsFullPayoutAndBumpsReputation() {
		GameState state = newState();
		PlacementResult.Success success = (PlacementResult.Success) state.placeBuilding(
			BuildingKind.RESTAURANT, 47.6, -122.3, RECIPE_ID, TEMPLATE_ID);
		UUID restaurantId = success.building().id();
		Restaurant placed = (Restaurant) success.building();

		Order order = state.enqueueOrder(restaurantId, RECIPE_ID, 1, 60).orElseThrow();
		Optional<GameState.OrderOutcome> outcome = state.fulfillOrder(restaurantId, order.id());

		assertTrue(outcome.isPresent());
		assertEquals(OrderResult.FULFILLED, outcome.get().result());
		assertEquals(BASE_PAYOUT, outcome.get().payout());
		assertEquals(success.newBalance().amount() + BASE_PAYOUT, outcome.get().newBalance());
		assertEquals(
			Math.min(1.0, placed.reputation() + GameConstants.REPUTATION_GAIN_ON_TIME),
			outcome.get().newReputation(),
			1e-9);
	}

	@Test
	void fulfilledLate_appliesDiscountAndDocksReputation() {
		GameState state = newState();
		PlacementResult.Success success = (PlacementResult.Success) state.placeBuilding(
			BuildingKind.RESTAURANT, 47.6, -122.3, RECIPE_ID, TEMPLATE_ID);
		UUID restaurantId = success.building().id();
		Order order = state.enqueueOrder(restaurantId, RECIPE_ID, 1, 5).orElseThrow();

		// Push the clock past the deadline before fulfilling.
		state.advanceClock(10);
		GameState.OrderOutcome outcome = state.fulfillOrder(restaurantId, order.id()).orElseThrow();

		assertEquals(OrderResult.LATE, outcome.result());
		assertEquals(
			Math.round(BASE_PAYOUT * GameConstants.LATE_DELIVERY_PAYOUT_MULTIPLIER),
			outcome.payout());
		assertTrue(outcome.newReputation() < 1.0);
	}

	@Test
	void expirePendingOrders_drainsAndHitsReputation() {
		GameState state = newState();
		PlacementResult.Success success = (PlacementResult.Success) state.placeBuilding(
			BuildingKind.RESTAURANT, 47.6, -122.3, RECIPE_ID, TEMPLATE_ID);
		UUID restaurantId = success.building().id();
		state.enqueueOrder(restaurantId, RECIPE_ID, 1, 5).orElseThrow();
		state.advanceClock(10);

		List<GameState.ExpiredOrderEvent> events = state.expirePendingOrders();
		assertEquals(1, events.size());
		assertTrue(events.get(0).newReputation() < 1.0);
		assertTrue(state.listOrders(restaurantId).isEmpty());

		// Idempotent — second drain returns nothing.
		assertTrue(state.expirePendingOrders().isEmpty());
	}

	@Test
	void fulfillUnknownOrder_returnsEmpty() {
		GameState state = newState();
		PlacementResult.Success success = (PlacementResult.Success) state.placeBuilding(
			BuildingKind.RESTAURANT, 47.6, -122.3, RECIPE_ID, TEMPLATE_ID);
		assertNotNull(success);
		UUID restaurantId = success.building().id();

		assertFalse(state.fulfillOrder(restaurantId, UUID.randomUUID()).isPresent());
	}
}



