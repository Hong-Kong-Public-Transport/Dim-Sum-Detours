package com.dimsumdetours.engine.routing;

import com.dimsumdetours.gtfs.GtfsLoader;
import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.state.RoutePlan;
import com.dimsumdetours.sim.state.RouteProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase-21 unit tests for {@link RoutePlanner}'s direct-vs-NoPath
 * branching when no GTFS feed is loaded (the transit branch
 * naturally returns {@code null}). End-to-end transit planning is
 * covered indirectly by {@code GameStateCargoEventsTest} via a stub
 * {@link com.dimsumdetours.sim.state.TransitSchedule} — fixturing the
 * legacy {@code GtfsRelationalDaoImpl} for an isolated planner test
 * adds more setup than it removes.
 */
class RoutePlannerTest {

	private static final LatLon HK_NEAR_A = new LatLon(22.30, 114.17);
	private static final LatLon HK_NEAR_B = new LatLon(22.31, 114.18); // ~1.4 km away
	private static final LatLon HK_FAR_B = new LatLon(22.40, 114.30);  // ~17 km away

	private static RoutePlanner newPlannerWithNoFeed(RouteProvider router) {
		GtfsLoader loader = Mockito.mock(GtfsLoader.class);
		when(loader.listFeeds()).thenReturn(new it.unimi.dsi.fastutil.objects.ObjectArrayList<>());
		RoutePlanner planner = new RoutePlanner(loader, router);
		planner.initialise();
		return planner;
	}

	@Test
	void fallsBackToDirectRobotWhenNoTransitAndShortHop() {
		RouteProvider router = Mockito.mock(RouteProvider.class);
		when(router.findPath(HK_NEAR_A, HK_NEAR_B))
			.thenReturn(List.of(HK_NEAR_A, HK_NEAR_B));

		RoutePlan plan = newPlannerWithNoFeed(router).plan(HK_NEAR_A, HK_NEAR_B);

		assertThat(plan).isInstanceOf(RoutePlan.DirectRobot.class);
		RoutePlan.DirectRobot direct = (RoutePlan.DirectRobot) plan;
		assertThat(direct.path()).hasSize(2);
		assertThat(direct.durationGameMinutes()).isGreaterThanOrEqualTo(1L);
	}

	@Test
	void returnsNoPathWhenNoTransitAndHaversineExceedsCap() {
		RouteProvider router = Mockito.mock(RouteProvider.class);

		RoutePlan plan = newPlannerWithNoFeed(router).plan(HK_NEAR_A, HK_FAR_B);

		assertThat(plan).isInstanceOf(RoutePlan.NoPath.class);
		// Direct fallback gated on haversine — must NOT consult router.
		Mockito.verifyNoInteractions(router);
	}

	@Test
	void returnsNoPathWhenShortHopButRouterUnreachable() {
		RouteProvider router = Mockito.mock(RouteProvider.class);
		when(router.findPath(any(), any())).thenReturn(null);

		RoutePlan plan = newPlannerWithNoFeed(router).plan(HK_NEAR_A, HK_NEAR_B);

		assertThat(plan).isInstanceOf(RoutePlan.NoPath.class);
	}
}

