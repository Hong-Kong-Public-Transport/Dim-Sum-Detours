package com.dimsumdetours.sim.state;

import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * Phase-21 SPI: ambient-transit timetable lookup for the boarding state
 * machine. Lives in framework-agnostic {@code sim/} so {@link GameState}
 * can talk to GTFS data without dragging the Spring layer into the
 * simulation core. Mirrors the existing {@link RouteProvider} pattern —
 * the engine layer ({@code SnapshotTransitSchedule}) wraps the
 * {@code TransitSnapshotService} snapshot at boot.
 *
 * <p>"Ambient" means: every route runs a vehicle on a flat
 * {@link com.dimsumdetours.config.GameConstants#BUS_HEADWAY_GAME_MINUTES}
 * cadence regardless of the published schedule (Phase 18 design choice
 * — keeps transit always-eventually-available so the player isn't
 * locked out by an in-game 03:00 service gap). For a route with
 * stop-at-index-{@code i} relative arrival time {@code rel[i]} game-min
 * since the run started, the run that departed at {@code k·H} reaches
 * stop[i] at {@code k·H + rel[i]}.
 *
 * <p>The boarding state machine asks two questions:
 * <ol>
 *   <li>"Did any run on {@code routeId} cross stop {@code stopId}
 *       inside this tick window?" — answered by
 *       {@link #findRunCrossingStop}; the dispatcher then attaches the
 *       waiting cargo to that run.</li>
 *   <li>"For an active run carrying cargo, when does it reach the
 *       alighting stop?" — answered by {@link #arrivalAtStop}; the
 *       alighting scan compares this against the current game-minute
 *       to decide when to drop the manifest.</li>
 * </ol>
 *
 * <p>The default {@link #disabled()} implementation answers "no run /
 * never arrives" for every query, which is the right baseline for
 * tests + headless integrations without GTFS data on disk. Production
 * wires in the {@code SnapshotTransitSchedule} bean.
 */
public interface TransitSchedule {

	/**
	 * Find the earliest ambient run on {@code routeId} whose vehicle
	 * arrives at stop {@code stopId} with arrival game-minute in
	 * {@code (windowStartExclusive, windowEndInclusive]}. The exclusive
	 * lower bound matches the boarding-scan semantics in
	 * {@link GameState#advanceVehicles(long)} — the previous tick already
	 * had its chance to capture runs at {@code lastTickGameMinutes}; we
	 * must not double-count those.
	 *
	 * <p>Returns {@code null} when no run on this route serves this stop
	 * inside the window (or the schedule is disabled).
	 */
	@Nullable RunArrival findRunCrossingStop(
		String routeId, String stopId,
		long windowStartExclusive, long windowEndInclusive);

	/**
	 * For the run with departure offset {@code departureOffsetGameMinutes}
	 * on {@code routeId}, return the absolute game-minute it arrives at
	 * {@code stopId}. Used by the alighting scan to know <em>when</em>
	 * the cargo can be dropped at its planned alighting stop.
	 *
	 * <p>Empty when the route or stop is unknown to the schedule.
	 */
	OptionalLong arrivalAtStop(String routeId, long departureOffsetGameMinutes, String stopId);

	/** Result of {@link #findRunCrossingStop}. */
	record RunArrival(
		long departureOffsetGameMinutes,
		int gtfsRouteType,
		long arrivalGameMinutes
	) {
	}

	/**
	 * No-op schedule. Returns "no run / never arrives" for every query;
	 * boarding scans become silent no-ops, waiting cargo accumulates
	 * indefinitely. Suitable for tests + headless integrations.
	 */
	static TransitSchedule disabled() {
		return new TransitSchedule() {
			@Override public @Nullable RunArrival findRunCrossingStop(
				String routeId, String stopId, long start, long end) {
				return null;
			}
			@Override public OptionalLong arrivalAtStop(
				String routeId, long departureOffset, String stopId) {
				return OptionalLong.empty();
			}
		};
	}
}

