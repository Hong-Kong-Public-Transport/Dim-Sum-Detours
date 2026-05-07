package com.dimsumdetours.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.engine.routing.RoutePlanner;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.RoutePlan;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase-21 test coverage closure: when {@link RoutePlanner#plan} returns
 * {@link RoutePlan.NoPath}, the dispatcher must skip the shipment without
 * spawning a vehicle and without debiting the producer's accumulated
 * stock. The producer keeps its units so a future tick (e.g. once a
 * transit feed is loaded, or once a closer producer accumulates enough
 * stock) can still ship them.
 */
class VehicleDispatcherFailedDispatchTest {

	private static ContentRegistry seededRegistry() {
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
		return registry;
	}

	private static RoutePlanner alwaysNoPathPlanner() {
		RoutePlanner planner = Mockito.mock(RoutePlanner.class);
		when(planner.plan(any(), any())).thenReturn(RoutePlan.NoPath.INSTANCE);
		return planner;
	}

	@Test
	void noPath_leavesProducerStockUnchanged() {
		ContentRegistry registry = seededRegistry();
		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);

		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.80, -122.50, "cooked_rice", null)).building();

		// 60 game-min → 6 units on a 10-min cycle, comfortably past
		// VehicleDispatcher#hasStock's batch-size requirement.
		state.advanceClock(60L);
		state.advanceProduction();
		Farm farmBefore = (Farm) findById(state, farm.id());
		long stockBefore = farmBefore.producedUnits();
		assertThat(stockBefore).isGreaterThanOrEqualTo(GameConstants.ROBOT_CARGO_BATCH_SIZE);

		VehicleDispatcher dispatcher = new VehicleDispatcher(
			state, registry, alwaysNoPathPlanner());

		List<VehicleEvent.Spawned> spawned = dispatcher.dispatch();

		assertThat(spawned).isEmpty();
		assertThat(state.listVehicles()).isEmpty();
		Farm farmAfter = (Farm) findById(state, farm.id());
		assertThat(farmAfter.producedUnits()).isEqualTo(stockBefore);
		Factory factoryAfter = (Factory) findById(state, factory.id());
		assertThat(factoryAfter.inputStockpile().getOrDefault("rice", 0)).isZero();
		assertThat(dispatcher.failedDispatches()).isGreaterThanOrEqualTo(1L);
	}

	@Test
	void directRobot_doesShipAndDebit() {
		ContentRegistry registry = seededRegistry();
		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);

		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.61, -122.31, "cooked_rice", null)).building();
		assertThat(GameState.haversineMetres(farm.lat(), farm.lon(), factory.lat(), factory.lon()))
			.isLessThan(GameConstants.MAX_ROBOT_LEG_METERS);

		state.advanceClock(60L);
		state.advanceProduction();
		long stockBefore = ((Farm) findById(state, farm.id())).producedUnits();

		// Mock the planner to return a DirectRobot plan — sanity counter-test
		// that the spawn / debit path fires when the planner says yes.
		RoutePlanner planner = Mockito.mock(RoutePlanner.class);
		when(planner.plan(any(), any())).thenReturn(new RoutePlan.DirectRobot(
			List.of(
				new com.dimsumdetours.sim.model.LatLon(farm.lat(), farm.lon()),
				new com.dimsumdetours.sim.model.LatLon(factory.lat(), factory.lon())),
			3L));

		VehicleDispatcher dispatcher = new VehicleDispatcher(state, registry, planner);
		List<VehicleEvent.Spawned> spawned = dispatcher.dispatch();

		assertThat(spawned).isNotEmpty();
		assertThat(((Farm) findById(state, farm.id())).producedUnits()).isLessThan(stockBefore);
		assertThat(dispatcher.failedDispatches()).isZero();
	}

	@Test
	void chronicNoPath_logsAtMostOncePerTuplePerWindow() {
		ContentRegistry registry = seededRegistry();
		GameState state = new GameState(registry, Money.of(100_000L));
		state.setClockPaused(false);

		Farm farm = (Farm) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FARM, 47.60, -122.30, "grow_rice", null)).building();
		Factory factory = (Factory) ((PlacementResult.Success) state.placeBuilding(
			BuildingKind.FACTORY, 47.80, -122.50, "cooked_rice", null)).building();
		assertThat(GameState.haversineMetres(farm.lat(), farm.lon(), factory.lat(), factory.lon()))
			.isGreaterThan(GameConstants.MAX_ROBOT_LEG_METERS);
		state.advanceClock(60L);
		state.advanceProduction();

		VehicleDispatcher dispatcher = new VehicleDispatcher(
			state, registry, alwaysNoPathPlanner());

		Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(VehicleDispatcher.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		dispatcherLogger.addAppender(appender);
		try {
			for (int i = 0; i < 25; i++) {
				dispatcher.dispatch();
			}
		} finally {
			dispatcherLogger.detachAppender(appender);
		}

		long noPathLogLines = appender.list.stream()
			.filter(e -> e.getLevel() == Level.INFO)
			.filter(e -> e.getFormattedMessage().startsWith("[no-path/"))
			.count();
		assertThat(noPathLogLines)
			.as("chronic NoPath for the same (producer, consumer, ingredient) triple "
				+ "must throttle to ≤ 1 log line per 60s window")
			.isEqualTo(1L);
		assertThat(dispatcher.failedDispatches()).isGreaterThanOrEqualTo(25L);
	}

	private static Object findById(GameState state, java.util.UUID id) {
		return state.listBuildings().stream()
			.filter(b -> b.id().equals(id))
			.findFirst()
			.orElseThrow();
	}
}

