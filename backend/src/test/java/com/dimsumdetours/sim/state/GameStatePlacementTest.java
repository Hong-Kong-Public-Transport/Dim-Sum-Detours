package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-6 regression tests for placement edge cases beyond the order lifecycle:
 * <ul>
 *   <li>The density cap rejects same-kind buildings within the spacing radius but allows
 *       cross-kind neighbours and same-kind builds far apart.</li>
 *   <li>Restaurants are exempt from the {@code RECIPE_TIER_TOO_HIGH} gate (they merely accept
 *       deliveries of the recipe).</li>
 * </ul>
 */
class GameStatePlacementTest {

	private static final String FARM_RECIPE = "garlic_grow";
	private static final String HIGH_TIER_RECIPE = "fancy_dim_sum";

	private GameState newState() {
		ContentRegistry registry = new ContentRegistry();
		registry.putRecipe(new Recipe(
			FARM_RECIPE,
			Map.of("en", "Grow garlic"),
			List.of(),
			List.of("grow"),
			List.of(new RecipeIngredient("garlic", 1)),
			1,
			10,
			List.of()));
		// Tier-3 recipe — would be rejected for factories, but a restaurant should still accept it.
		registry.putRecipe(new Recipe(
			HIGH_TIER_RECIPE,
			Map.of("en", "Fancy dim sum"),
			List.of(),
			List.of("steam"),
			List.of(new RecipeIngredient("fancy_dim_sum", 1)),
			3,
			60,
			List.of()));
		registry.putRestaurantTemplate(new RestaurantTemplate(
			"prestige_diner",
			Map.of("en", "Prestige Diner"),
			List.of(HIGH_TIER_RECIPE),
			120,
			2_000L,
			List.of()));
		return new GameState(registry, Money.of(100_000L));
	}

	@Test
	void densityCap_rejectsTooCloseSameKind() {
		GameState state = newState();
		PlacementResult first = state.placeBuilding(BuildingKind.FARM, 47.6000, -122.3000, FARM_RECIPE, null);
		assertInstanceOf(PlacementResult.Success.class, first);

		// ~22 m east at this latitude — well within the 100 m cap.
		PlacementResult second = state.placeBuilding(BuildingKind.FARM, 47.6000, -122.29970, FARM_RECIPE, null);
		assertInstanceOf(PlacementResult.Failure.class, second);
		assertEquals(PlacementError.TOO_CLOSE_TO_EXISTING_BUILDING,
			((PlacementResult.Failure) second).error());
	}

	@Test
	void densityCap_allowsSameKindFarApart() {
		GameState state = newState();
		assertInstanceOf(PlacementResult.Success.class,
			state.placeBuilding(BuildingKind.FARM, 47.6000, -122.3000, FARM_RECIPE, null));
		// ~1 km away.
		assertInstanceOf(PlacementResult.Success.class,
			state.placeBuilding(BuildingKind.FARM, 47.6090, -122.3000, FARM_RECIPE, null));
	}

	@Test
	void densityCap_allowsDifferentKindAtSameSpot() {
		GameState state = newState();
		assertInstanceOf(PlacementResult.Success.class,
			state.placeBuilding(BuildingKind.FARM, 47.6000, -122.3000, FARM_RECIPE, null));
		assertInstanceOf(PlacementResult.Success.class,
			state.placeBuilding(BuildingKind.RESTAURANT, 47.6000, -122.3000, HIGH_TIER_RECIPE, "prestige_diner"));
	}

	@Test
	void restaurantPlacement_isExemptFromTierGate() {
		GameState state = newState();
		PlacementResult result = state.placeBuilding(
			BuildingKind.RESTAURANT, 47.6, -122.3, HIGH_TIER_RECIPE, "prestige_diner");
		assertInstanceOf(PlacementResult.Success.class, result);
	}

	@Test
	void factoryPlacement_stillBlockedByTierGate() {
		GameState state = newState();
		PlacementResult result = state.placeBuilding(
			BuildingKind.FACTORY, 47.6, -122.3, HIGH_TIER_RECIPE, null);
		assertInstanceOf(PlacementResult.Failure.class, result);
		assertTrue(((PlacementResult.Failure) result).error() == PlacementError.RECIPE_TIER_TOO_HIGH);
	}
}

