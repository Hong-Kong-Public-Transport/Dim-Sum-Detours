package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-8 production cycle + inventory-aware dispatch tests.
 *
 * <ul>
 *   <li>{@link GameState#advanceProduction()} increments {@code producedUnits} once a farm's
 *       cycle duration has elapsed in game-minutes.</li>
 *   <li>{@link GameState#tryConsumeProducedUnit(UUID)} decrements stock atomically and
 *       returns empty when the source is dry — the precise contract the delivery dispatcher
 *       relies on for the inventory-aware re-park logic.</li>
 * </ul>
 */
class GameStateProductionTest {

	private static final String FARM_RECIPE = "garlic_grow";

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
		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);
		return state;
	}

	@Test
	void advanceProduction_incrementsProducedUnitsAfterCycleElapses() {
		GameState state = newState();
		PlacementResult result = state.placeBuilding(
			BuildingKind.FARM, 47.6, -122.3, FARM_RECIPE, null);
		assertInstanceOf(PlacementResult.Success.class, result);
		Farm farm = (Farm) ((PlacementResult.Success) result).building();
		// Phase-11: a freshly-placed farm starts empty. The first cycle still completes
		// after `cycleDurationGameMinutes` of game-time, no free starter unit.
		assertEquals(0L, farm.producedUnits());

		// Cycle duration is the recipe's operationDurationMinutes (10) for farms.
		state.advanceClock(25L);
		state.advanceProduction();
		Farm updated = (Farm) state.listBuildings().stream()
			.filter(b -> b.id().equals(farm.id())).findFirst().orElseThrow();
		assertEquals(2L, updated.producedUnits());
	}

	@Test
	void tryConsumeProducedUnit_decrementsStockAndRefusesWhenDry() {
		GameState state = newState();
		PlacementResult result = state.placeBuilding(
			BuildingKind.FARM, 47.6, -122.3, FARM_RECIPE, null);
		Farm farm = (Farm) ((PlacementResult.Success) result).building();

		// Empty stockpile: first consume refuses.
		assertFalse(state.tryConsumeProducedUnit(farm.id()).isPresent());

		// One cycle later → 1 unit in stock.
		state.advanceClock(10L);
		state.advanceProduction();

		Optional<Building> consumed = state.tryConsumeProducedUnit(farm.id());
		assertTrue(consumed.isPresent());
		assertEquals(0L, ((Farm) consumed.get()).producedUnits());

		// Second consume on the same tick fails.
		assertFalse(state.tryConsumeProducedUnit(farm.id()).isPresent());
	}

	@Test
	void tryConsumeProducedUnit_returnsEmptyForUnknownId() {
		GameState state = newState();
		assertFalse(state.tryConsumeProducedUnit(UUID.randomUUID()).isPresent());
	}

	@Test
	void factory_doesNotProduceWithoutInputs_andStockpileGatesCycles() {
		// Phase-10 bug fix: factories must consume real input quantities — they no longer
		// produce regardless of inputs the way the Phase-8 prototype did.
		ContentRegistry registry = new ContentRegistry();
		registry.putRecipe(new Recipe(
			"grow_rice",
			Map.of("en", "Grow rice"),
			List.of(),
			List.of("grow"),
			List.of(new RecipeIngredient("rice", 1)),
			1, 10, List.of()));
		registry.putRecipe(new Recipe(
			"cooked_rice",
			Map.of("en", "Cook rice"),
			List.of(new RecipeIngredient("rice", 1)),
			List.of("cook"),
			List.of(new RecipeIngredient("cooked_rice", 1)),
			1, 5, List.of()));

		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);
		PlacementResult placement = state.placeBuilding(
			BuildingKind.FACTORY, 47.6, -122.3, "cooked_rice", null);
		Factory factory = (Factory) ((PlacementResult.Success) placement).building();
		// Phase-11: a freshly-placed factory starts empty — no starter unit, no
		// stockpile. The "produced count starts at 1 even though there's nothing to
		// supply it" complaint is fixed by Factory.of returning producedUnits = 0L.
		assertEquals(0L, factory.producedUnits());
		assertTrue(factory.inputStockpile().isEmpty());

		// Several cycles' worth of game-time pass with no input deliveries → still no
		// produced units, because the cycle gate refuses without rice on hand. Phase-11
		// also re-anchors the cycle-start to "now" while stalled so the progress ring
		// stays at 0% rather than spinning through phantom progress.
		state.advanceClock(20L);
		state.advanceProduction();
		Factory after = (Factory) state.listBuildings().stream()
			.filter(b -> b.id().equals(factory.id())).findFirst().orElseThrow();
		assertEquals(0L, after.producedUnits(),
			"factory must not produce when its input stockpile is empty");

		// Deliver two rice → after enough clock-time elapses for two cycles, the factory
		// completes both (one per available input), draining the stockpile, and
		// producedUnits jumps from 0 to 2.
		state.tryDeliverInputToFactory(factory.id(), "rice", 2);
		state.advanceClock(10L);
		state.advanceProduction();
		Factory after2 = (Factory) state.listBuildings().stream()
			.filter(b -> b.id().equals(factory.id())).findFirst().orElseThrow();
		assertEquals(2L, after2.producedUnits());
		assertEquals(0, after2.inputStockpile().getOrDefault("rice", 0));
	}

	@Test
	void tryDeliverInputToFactory_returnsEmptyForUnknownIdOrNonFactory() {
		GameState state = newState();
		assertFalse(state.tryDeliverInputToFactory(java.util.UUID.randomUUID(), "rice", 1).isPresent());
		// A farm is not a factory:
		PlacementResult result = state.placeBuilding(
			BuildingKind.FARM, 47.6, -122.3, FARM_RECIPE, null);
		Farm farm = (Farm) ((PlacementResult.Success) result).building();
		assertFalse(state.tryDeliverInputToFactory(farm.id(), "garlic", 1).isPresent());
	}
}
