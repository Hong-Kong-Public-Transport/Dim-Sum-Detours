package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.Vehicle;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-12 vehicle entity tests. Spawning debits the source's produced stock,
 * advancing past the arrival deadline applies the cargo to the destination factory's
 * input stockpile, and out-of-stock sources refuse to spawn.
 */
class GameStateVehicleTest {

	private static GameState newState() {
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
		return state;
	}

	@Test
	void spawnRobot_debitsSourceAndCreditsFactoryOnArrival() {
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();

		// Generate one rice on the farm.
		state.advanceClock(10L);
		state.advanceProduction();

		Optional<VehicleEvent.Spawned> spawned =
			state.spawnRobot(farm.id(), factory.id(), "rice", 1, null, null);
		assertTrue(spawned.isPresent(), "spawn should succeed when farm has stock");

		// Source was debited.
		Farm afterSpawn = (Farm) state.listBuildings().stream()
			.filter(b -> b.id().equals(farm.id())).findFirst().orElseThrow();
		assertEquals(0L, afterSpawn.producedUnits());

		// Vehicle is in flight.
		List<Vehicle> inflight = state.listVehicles();
		assertEquals(1, inflight.size());
		Robot robot = (Robot) inflight.get(0);
		assertEquals(factory.id(), robot.destinationBuildingId());

		// Advance the clock past the arrival deadline. advanceVehicles() removes the
		// robot and credits the factory's input stockpile.
		long travel = robot.arrivesAtGameMinutes() - robot.spawnedAtGameMinutes();
		state.advanceClock(travel + 1);
		GameState.ArrivalBatch batch = state.advanceVehicles();
		assertEquals(1, batch.vehicleEvents().size());
		assertTrue(state.listVehicles().isEmpty(), "vehicle should be removed on arrival");

		Factory afterArrival = (Factory) state.listBuildings().stream()
			.filter(b -> b.id().equals(factory.id())).findFirst().orElseThrow();
		assertEquals(1, afterArrival.inputStockpile().getOrDefault("rice", 0));
	}

	@Test
	void spawnRobot_refusesWhenSourceHasNoStock() {
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();

		// No production yet → stock is zero.
		Optional<VehicleEvent.Spawned> spawned =
			state.spawnRobot(farm.id(), factory.id(), "rice", 1, null, null);
		assertFalse(spawned.isPresent(), "spawn must refuse on empty source");
		assertTrue(state.listVehicles().isEmpty());
	}

	@Test
	void hasActiveRestock_dedupesByLeg() {
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();

		state.advanceClock(20L);
		state.advanceProduction();

		assertFalse(state.hasActiveRestock(farm.id(), factory.id(), "rice"));
		state.spawnRobot(farm.id(), factory.id(), "rice", 1, null, null);
		assertTrue(state.hasActiveRestock(farm.id(), factory.id(), "rice"));

		// Reset clears active restocks.
		state.reset();
		assertFalse(state.hasActiveRestock(farm.id(), factory.id(), "rice"));
		assertTrue(state.listVehicles().isEmpty());
	}

	@Test
	void hasInFlightOrder_tracksRestaurantBoundRobots() {
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		// Stand-in restaurant — placed via the factory call to keep the test focused on
		// the in-flight tracking rather than restaurant content. We just need a
		// destination id whose presence in the vehicle map flips the predicate.
		UUID fakeOrderId = UUID.randomUUID();
		assertFalse(state.hasInFlightOrder(fakeOrderId));

		state.advanceClock(10L);
		state.advanceProduction();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();

		state.spawnRobot(farm.id(), factory.id(), "rice", 1, fakeOrderId, null);
		assertTrue(state.hasInFlightOrder(fakeOrderId));
	}
}

