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
import com.dimsumdetours.sim.model.vehicle.CargoEvent;
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.TransitBoarding;
import com.dimsumdetours.sim.model.vehicle.TransitRunId;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-21 cargo lifecycle tests. Drives the boarding state machine
 * with a stub {@link TransitSchedule} so the asserted SSE emissions
 * are deterministic without a real GTFS feed.
 */
class GameStateCargoEventsTest {

	private static GameState newState() {
		ContentRegistry registry = new ContentRegistry();
		registry.putRecipe(new Recipe(
			"grow_rice", Map.of("en", "Grow rice"), List.of(),
			List.of("grow"), List.of(new RecipeIngredient("rice", 1)),
			1, 10, List.of()));
		registry.putRecipe(new Recipe(
			"cooked_rice", Map.of("en", "Cook rice"),
			List.of(new RecipeIngredient("rice", 1)),
			List.of("cook"), List.of(new RecipeIngredient("cooked_rice", 1)),
			1, 5, List.of()));
		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);
		return state;
	}

	private static TransitBoarding boardingTo(LatLon alighting, LatLon dest) {
		return new TransitBoarding(
			"route-A", "stop-board", "stop-alight",
			List.of(alighting, dest), 5L);
	}

	@Test
	void firstMileRobotArrival_emitsRobotArrivedAtStopOnMiscChannel() {
		// Phase-21 close-out regression: the first-mile robot's despawn
		// frame travels on {@link GameState.ArrivalBatch#miscEvents()},
		// not the normal arrived/spawn channels. The previous
		// {@link com.dimsumdetours.engine.SimulationEngine#tick} body
		// never iterated this list, so the frontend never received the
		// despawn — the waiting robot lingered on the map even though
		// its cargo had successfully flipped into the WaitingCargo queue
		// and the connecting robot at the alighting stop had spawned.
		GameState state = newState();
		state.setTransitSchedule(new StubSchedule(0L, 8L));
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

		assertTrue(state.spawnTransitFirstLeg(farm.id(), factory.id(), "rice", 1,
			List.of(source, boarding), 4L,
			boardingTo(alighting, dest), null, null).isPresent());

		Robot firstRobot = (Robot) state.listVehicles().get(0);
		long lastTick = state.getClockSnapshot().gameMinutes();
		state.advanceClock(firstRobot.arrivesAtGameMinutes() - lastTick + 1);
		GameState.ArrivalBatch batch = state.advanceVehicles(lastTick);

		assertEquals(1, batch.miscEvents().size(),
			"first-mile arrival must produce exactly one misc event");
		VehicleEvent.RobotArrivedAtStop event = assertInstanceOf(
			VehicleEvent.RobotArrivedAtStop.class, batch.miscEvents().get(0));
		assertEquals(firstRobot.id(), event.vehicleId());
		assertEquals("stop-board", event.boardingStopId());
		assertEquals("route-A", event.routeId());
	}

	@Test
	void cargoLifecycle_emitsRunStartedLoadedUnloadedFinished() {
		GameState state = newState();
		state.setTransitSchedule(new StubSchedule(0L, 8L));
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

		Optional<VehicleEvent.Spawned> first = state.spawnTransitFirstLeg(
			farm.id(), factory.id(), "rice", 1,
			List.of(source, boarding), 4L,
			boardingTo(alighting, dest), null, null);
		assertTrue(first.isPresent(), "first leg should spawn when source has stock");

		// Tick 1: first-mile robot arrives → boarding scan picks up the
		// run → RUN_STARTED + CARGO_LOADED.
		Robot firstRobot = (Robot) state.listVehicles().get(0);
		long lastTick = state.getClockSnapshot().gameMinutes();
		state.advanceClock(firstRobot.arrivesAtGameMinutes() - lastTick + 1);
		List<CargoEvent> board = new ArrayList<>(state.advanceVehicles(lastTick).cargoEvents());

		assertEquals(2, board.size(),
			"boarding tick must emit RUN_STARTED + CARGO_LOADED, got " + board);
		CargoEvent.RunStarted runStarted = assertInstanceOf(CargoEvent.RunStarted.class, board.get(0));
		CargoEvent.CargoLoaded cargoLoaded = assertInstanceOf(CargoEvent.CargoLoaded.class, board.get(1));
		assertEquals("route-A", runStarted.run().routeId());
		assertEquals(runStarted.run(), cargoLoaded.run());
		assertEquals(1, cargoLoaded.totalCargoUnits());
		assertNotNull(cargoLoaded.manifestId());

		// Tick 2: alighting at run + 8 → CARGO_UNLOADED + RUN_FINISHED.
		long lastTick2 = state.getClockSnapshot().gameMinutes();
		state.advanceClock(/* alightDelta + 1 */ 9L);
		List<CargoEvent> alight = new ArrayList<>(state.advanceVehicles(lastTick2).cargoEvents());

		assertEquals(2, alight.size(),
			"alighting tick must emit CARGO_UNLOADED + RUN_FINISHED, got " + alight);
		CargoEvent.CargoUnloaded cargoUnloaded = assertInstanceOf(CargoEvent.CargoUnloaded.class, alight.get(0));
		CargoEvent.RunFinished runFinished = assertInstanceOf(CargoEvent.RunFinished.class, alight.get(1));
		assertEquals(cargoLoaded.manifestId(), cargoUnloaded.manifestId());
		assertEquals(0, cargoUnloaded.totalCargoUnits());
		assertEquals(runStarted.run(), runFinished.run());

		// Connecting robot's terminal arrival shouldn't touch cargo stream.
		Robot finalRobot = (Robot) state.listVehicles().get(0);
		long lastTick3 = state.getClockSnapshot().gameMinutes();
		state.advanceClock(finalRobot.arrivesAtGameMinutes() - lastTick3 + 1);
		List<CargoEvent> terminal = state.advanceVehicles(lastTick3).cargoEvents();
		assertEquals(0, terminal.size());
	}

	@Test
	void multiManifestRun_emitsRunFinishedOnlyAfterLastUnload() {
		GameState state = newState();
		state.setTransitSchedule(new StubSchedule(0L, 8L));
		Farm farmA = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Farm farmB = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.601, -122.301, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.80, -122.50, "cooked_rice", null)).building();

		state.advanceClock(10L);
		state.advanceProduction();

		LatLon boarding = new LatLon(47.65, -122.35);
		LatLon alighting = new LatLon(47.75, -122.45);
		LatLon dest = new LatLon(factory.lat(), factory.lon());

		LatLon sourceA = new LatLon(farmA.lat(), farmA.lon());
		LatLon sourceB = new LatLon(farmB.lat(), farmB.lon());
		assertTrue(state.spawnTransitFirstLeg(farmA.id(), factory.id(), "rice", 1,
			List.of(sourceA, boarding), 4L,
			boardingTo(alighting, dest), null, null).isPresent());
		assertTrue(state.spawnTransitFirstLeg(farmB.id(), factory.id(), "rice", 1,
			List.of(sourceB, boarding), 4L,
			boardingTo(alighting, dest), null, null).isPresent());

		// Tick 1: both first-mile robots arrive, both manifests board the
		// same shared run → 1× RUN_STARTED + 2× CARGO_LOADED (totals 1 then 2).
		long firstArrival = ((Robot) state.listVehicles().get(0)).arrivesAtGameMinutes();
		long lastTick = state.getClockSnapshot().gameMinutes();
		state.advanceClock(firstArrival - lastTick + 1);
		List<CargoEvent> boardEvents = new ArrayList<>(state.advanceVehicles(lastTick).cargoEvents());

		long runStartedCount = boardEvents.stream()
			.filter(CargoEvent.RunStarted.class::isInstance).count();
		long cargoLoadedCount = boardEvents.stream()
			.filter(CargoEvent.CargoLoaded.class::isInstance).count();
		assertEquals(1L, runStartedCount,
			"shared run should emit exactly one RUN_STARTED across both manifests");
		assertEquals(2L, cargoLoadedCount,
			"each manifest should emit its own CARGO_LOADED");
		List<CargoEvent.CargoLoaded> loadedFrames = boardEvents.stream()
			.filter(CargoEvent.CargoLoaded.class::isInstance)
			.map(CargoEvent.CargoLoaded.class::cast)
			.toList();
		assertEquals(1, loadedFrames.get(0).totalCargoUnits());
		assertEquals(2, loadedFrames.get(1).totalCargoUnits());

		// Tick 2: both manifests alight together → 2× CARGO_UNLOADED + 1× RUN_FINISHED.
		long lastTick2 = state.getClockSnapshot().gameMinutes();
		state.advanceClock(/* alightDelta + 1 */ 9L);
		List<CargoEvent> alightEvents = new ArrayList<>(state.advanceVehicles(lastTick2).cargoEvents());

		long unloadedCount = alightEvents.stream()
			.filter(CargoEvent.CargoUnloaded.class::isInstance).count();
		long runFinishedCount = alightEvents.stream()
			.filter(CargoEvent.RunFinished.class::isInstance).count();
		assertEquals(2L, unloadedCount, "each manifest must emit its own CARGO_UNLOADED");
		assertEquals(1L, runFinishedCount,
			"RUN_FINISHED must fire exactly once even with multiple manifests on the run");

		// Phase-21 close-out: previously every CARGO_UNLOADED frame in a
		// multi-manifest alight reported the same post-all-removal total
		// (0), so the frontend's bus-sprite scale jumped straight to the
		// final value. The first frame must report a non-zero remaining
		// total so the sprite shrinks step-by-step.
		List<CargoEvent.CargoUnloaded> unloadFrames = alightEvents.stream()
			.filter(CargoEvent.CargoUnloaded.class::isInstance)
			.map(CargoEvent.CargoUnloaded.class::cast)
			.toList();
		assertEquals(1, unloadFrames.get(0).totalCargoUnits(),
			"first unload of two manifests must report 1 unit still aboard");
		assertEquals(0, unloadFrames.get(1).totalCargoUnits(),
			"second unload must drain the run total to 0");

		int lastUnloadIdx = -1;
		int runFinishedIdx = -1;
		for (int i = 0; i < alightEvents.size(); i++) {
			CargoEvent e = alightEvents.get(i);
			if (e instanceof CargoEvent.CargoUnloaded) {
				lastUnloadIdx = i;
			} else if (e instanceof CargoEvent.RunFinished) {
				runFinishedIdx = i;
			}
		}
		assertTrue(runFinishedIdx > lastUnloadIdx,
			"RUN_FINISHED must be emitted strictly after the final CARGO_UNLOADED");
		CargoEvent.CargoUnloaded finalUnload = (CargoEvent.CargoUnloaded) alightEvents.get(lastUnloadIdx);
		assertEquals(0, finalUnload.totalCargoUnits(),
			"final unload must drive the run total to 0 before RUN_FINISHED fires");
	}

	@Test
	void cargoTransitRunsSnapshot_reflectsLiveRunTotals() {
		GameState state = newState();
		state.setTransitSchedule(new StubSchedule(0L, 8L));
		assertTrue(state.cargoTransitRunsSnapshot().isEmpty(),
			"fresh world must report no in-flight transit runs");

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

		assertTrue(state.spawnTransitFirstLeg(farm.id(), factory.id(), "rice", 1,
			List.of(source, boarding), 4L,
			boardingTo(alighting, dest), null, null).isPresent());

		Robot firstRobot = (Robot) state.listVehicles().get(0);
		long lastTick = state.getClockSnapshot().gameMinutes();
		state.advanceClock(firstRobot.arrivesAtGameMinutes() - lastTick + 1);
		state.advanceVehicles(lastTick);

		Map<TransitRunId, Integer> snapshot = state.cargoTransitRunsSnapshot();
		assertEquals(1, snapshot.size());
		Map.Entry<TransitRunId, Integer> entry = snapshot.entrySet().iterator().next();
		assertEquals("route-A", entry.getKey().routeId());
		assertEquals(1, entry.getValue());

		long lastTick2 = state.getClockSnapshot().gameMinutes();
		state.advanceClock(/* alightDelta + 1 */ 9L);
		state.advanceVehicles(lastTick2);
		assertTrue(state.cargoTransitRunsSnapshot().isEmpty(),
			"after RUN_FINISHED the snapshot must drain back to empty");
	}

	/** Stub: any waiting cargo gets a run whose departure-offset = end of
	 * the query window (i.e. boarding happens at the end of the tick that
	 * called us); alighting happens {@code alightDelta} game-min after
	 * departure. Lets each test advance the clock by exactly
	 * {@code alightDelta + 1} game-min to drive the alighting scan. */
	private static final class StubSchedule implements TransitSchedule {
		private final long alightDelta;

		StubSchedule(long ignoredDepart, long alightDelta) {
			this.alightDelta = alightDelta;
		}

		@Override
		public @Nullable RunArrival findRunCrossingStop(
			String routeId, String stopId, long start, long end
		) {
			return new RunArrival(end, 3, end);
		}

		@Override
		public OptionalLong arrivalAtStop(
			String routeId, long departureOffsetGameMinutes, String stopId
		) {
			return OptionalLong.of(departureOffsetGameMinutes + alightDelta);
		}
	}
}

