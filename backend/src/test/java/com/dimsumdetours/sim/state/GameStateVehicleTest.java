package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.TransitBoarding;
import com.dimsumdetours.sim.model.vehicle.Vehicle;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-21 vehicle entity tests. Covers the direct-robot debit/credit
 * loop plus the new transit-first-leg → boarding → alighting state
 * machine, the latter driven via a stub {@link TransitSchedule}.
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

		state.advanceClock(10L);
		state.advanceProduction();

		Optional<VehicleEvent.Spawned> spawned =
			state.spawnRobot(farm.id(), factory.id(), "rice", 1, null, null);
		assertTrue(spawned.isPresent(), "spawn should succeed when farm has stock");

		Farm afterSpawn = (Farm) state.listBuildings().stream()
			.filter(b -> b.id().equals(farm.id())).findFirst().orElseThrow();
		assertEquals(0L, afterSpawn.producedUnits());

		List<Vehicle> inflight = state.listVehicles();
		assertEquals(1, inflight.size());
		Robot robot = (Robot) inflight.get(0);
		assertEquals(factory.id(), robot.destinationBuildingId());

		state.advanceClock(robot.arrivesAtGameMinutes() - state.getClockSnapshot().gameMinutes() + 1);
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

		state.reset();
		assertFalse(state.hasActiveRestock(farm.id(), factory.id(), "rice"));
		assertTrue(state.listVehicles().isEmpty());
	}

	@Test
	void hasInFlightOrder_tracksRestaurantBoundRobots() {
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		UUID fakeOrderId = UUID.randomUUID();
		assertFalse(state.hasInFlightOrder(fakeOrderId));

		state.advanceClock(10L);
		state.advanceProduction();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();

		state.spawnRobot(farm.id(), factory.id(), "rice", 1, fakeOrderId, null);
		assertTrue(state.hasInFlightOrder(fakeOrderId));
	}

	@Test
	void spawnTransitFirstLeg_drivesArrivalsBoardingAlightingChain() {
		// Phase-21 boarding state machine end-to-end:
		//   1. spawnTransitFirstLeg → first-mile robot in `vehicles`.
		//   2. advanceVehicles past arrival → robot removed, WaitingCargo
		//      enqueued at boarding stop, ROBOT_ARRIVED_AT_STOP emitted,
		//      stub TransitSchedule reports an in-window run → boarding
		//      scan drains the queue, RUN_STARTED + CARGO_LOADED emitted,
		//      no client-visible bus is created (server-side TransitVehicle
		//      only).
		//   3. advanceVehicles past alighting → CARGO_UNLOADED + RUN_FINISHED
		//      emitted, connecting robot spawned, walks to factory.
		//   4. advanceVehicles past final arrival → cargo applied to
		//      factory's input stockpile; restock dedup clears.
		GameState state = newState();
		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.80, -122.50, "cooked_rice", null)).building();

		state.advanceClock(10L);
		state.advanceProduction();

		LatLon source = new LatLon(farm.lat(), farm.lon());
		LatLon boarding = new LatLon(47.65, -122.35);
		LatLon alighting = new LatLon(47.75, -122.45);
		LatLon dest = new LatLon(factory.lat(), factory.lon());

		long firstLegDuration = 4L;
		long postTransitDuration = 5L;
		TransitBoarding board = new TransitBoarding(
			"route-A", "stop-board", "stop-alight",
			List.of(alighting, dest), postTransitDuration);

		// Stub schedule: any cargo waiting at stop-board gets picked up
		// at the END of its tick window (so the boarding scan inside the
		// arrivals tick sees the run); alighting at stop-alight happens
		// 8 game-min after the run started.
		state.setTransitSchedule(new StubSchedule(/* depart */ 0L, /* alightDelta */ 8L));

		Optional<VehicleEvent.Spawned> first = state.spawnTransitFirstLeg(
			farm.id(), factory.id(), "rice", 1,
			List.of(source, boarding), firstLegDuration, board, null, null);
		assertTrue(first.isPresent(), "first-mile spawn should succeed");
		// Restock dedup is set on (source, finalDest, ingredient) at spawn
		// time and stays set until terminal arrival.
		assertTrue(state.hasActiveRestock(farm.id(), factory.id(), "rice"));

		Robot firstRobot = (Robot) state.listVehicles().get(0);
		assertNotNull(firstRobot.boarding(), "first-mile robot must carry a TransitBoarding");

		// Tick 1: drive past the first-mile arrival.
		long lastTickBeforeArrival = state.getClockSnapshot().gameMinutes();
		state.advanceClock(firstRobot.arrivesAtGameMinutes()
			- lastTickBeforeArrival + 1);
		GameState.ArrivalBatch t1 = state.advanceVehicles(lastTickBeforeArrival);

		// One ROBOT_ARRIVED_AT_STOP misc event, the bus is server-side only
		// (no Spawned), and the boarding scan emitted RUN_STARTED + CARGO_LOADED.
		assertEquals(0, t1.vehicleEvents().size(),
			"first-mile arrival doesn't credit a destination → no Arrived event");
		assertEquals(1, t1.miscEvents().size(),
			"first-mile arrival emits exactly one ROBOT_ARRIVED_AT_STOP");
		assertTrue(t1.miscEvents().get(0) instanceof VehicleEvent.RobotArrivedAtStop);
		assertEquals(2, t1.cargoEvents().size(),
			"boarding scan in same tick must emit RUN_STARTED + CARGO_LOADED");
		assertEquals(1, state.cargoTransitRunsSnapshot().size(),
			"transitVehicles map should hold the live run");
		assertTrue(state.listVehicles().isEmpty(),
			"first-mile robot was removed; bus is server-side bookkeeping only");

		// Tick 2: drive past alighting (run starts at the previous tick's
		// `now` per StubSchedule; alight at depart + 8).
		long lastTickBeforeAlight = state.getClockSnapshot().gameMinutes();
		state.advanceClock(/* alightDelta */ 8L + 1L);
		GameState.ArrivalBatch t2 = state.advanceVehicles(lastTickBeforeAlight);

		assertEquals(2, t2.cargoEvents().size(),
			"alighting tick must emit CARGO_UNLOADED + RUN_FINISHED");
		assertEquals(1, t2.spawnEvents().size(),
			"alighting must spawn a connecting robot for the post-transit walk");
		assertTrue(state.cargoTransitRunsSnapshot().isEmpty(),
			"after RUN_FINISHED the snapshot drains back to empty");

		// Tick 3: drive past the connecting-robot arrival → cargo
		// credited to the factory; restock dedup clears.
		Robot connecting = (Robot) state.listVehicles().get(0);
		long lastTickBeforeFinal = state.getClockSnapshot().gameMinutes();
		state.advanceClock(connecting.arrivesAtGameMinutes()
			- lastTickBeforeFinal + 1);
		GameState.ArrivalBatch t3 = state.advanceVehicles(lastTickBeforeFinal);
		assertEquals(1, t3.vehicleEvents().size());
		assertTrue(state.listVehicles().isEmpty());
		Factory afterArrival = (Factory) state.listBuildings().stream()
			.filter(b -> b.id().equals(factory.id())).findFirst().orElseThrow();
		assertEquals(1, afterArrival.inputStockpile().getOrDefault("rice", 0));
		assertFalse(state.hasActiveRestock(farm.id(), factory.id(), "rice"),
			"restock dedup clears on terminal-leg arrival");
	}

	/** Stub: any waiting cargo gets a run whose departure-offset = end of
	 * the query window; alighting happens {@code alightDelta} game-min
	 * after departure. */
	private static final class StubSchedule implements TransitSchedule {
		private final long alightDelta;

		StubSchedule(long ignoredDepart, long alightDelta) {
			this.alightDelta = alightDelta;
		}

		@Override
		public @Nullable RunArrival findRunCrossingStop(
			String routeId, String stopId, long start, long end
		) {
			return new RunArrival(end, /* gtfsRouteType = */ 3, end);
		}

		@Override
		public OptionalLong arrivalAtStop(
			String routeId, long departureOffsetGameMinutes, String stopId
		) {
			return OptionalLong.of(departureOffsetGameMinutes + alightDelta);
		}
	}
}

