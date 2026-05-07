package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.LatLon;

import java.util.List;

/**
 * Phase-21 stub: explicit pathfinder output. Replaces
 * {@code Optional<VehicleChain>} from the legacy
 * {@code GtfsMultiLegPlanner} contract. The dispatcher pattern-matches
 * on the variant and the caller cannot accidentally treat a
 * {@code NoPath} the same as a "fall through to direct robot" — it has
 * to acknowledge the failure explicitly.
 *
 * <ul>
 *   <li>{@link DirectRobot} — robot-only trip, no transit involvement.
 *       {@code path} is the OSM route source → destination, total length
 *       must satisfy {@code haversine ≤ MAX_DIRECT_ROBOT_METERS = 5 km}
 *       (per-leg cap also enforces {@code path} length).</li>
 *   <li>{@link Transit} — first-mile robot to {@code boardingStopId},
 *       boards route {@code routeId}, alights at
 *       {@code alightingStopId}, last-mile robot to destination.
 *       <strong>Both</strong> robot legs must individually fit
 *       within {@code MAX_DIRECT_ROBOT_METERS = 5 km}.</li>
 *   <li>{@link NoPath} — no transit chain feasible AND haversine
 *       {@code ≥ 5 km}. Dispatcher counts as a failed dispatch and
 *       does not debit inventory.</li>
 * </ul>
 *
 * <p>No callers yet — wired into {@code RoutePlanner} +
 * {@code VehicleDispatcher.dispatchShipment} in Phase 21.
 */
public sealed interface RoutePlan {

	record DirectRobot(List<LatLon> path, long durationGameMinutes) implements RoutePlan {
		public DirectRobot {
			path = List.copyOf(path);
			if (path.size() < 2) {
				throw new IllegalArgumentException(
					"DirectRobot path must have at least 2 waypoints, got " + path.size());
			}
			if (durationGameMinutes < 0L) {
				throw new IllegalArgumentException(
					"DirectRobot durationGameMinutes must be non-negative, got "
						+ durationGameMinutes);
			}
		}
	}

	record Transit(
		List<LatLon> firstLegPath,
		long firstLegDurationGameMinutes,
		String routeId,
		String boardingStopId,
		String alightingStopId,
		List<LatLon> postTransitPath,
		long postTransitDurationGameMinutes
	) implements RoutePlan {
		public Transit {
			firstLegPath = List.copyOf(firstLegPath);
			postTransitPath = List.copyOf(postTransitPath);
			if (firstLegPath.size() < 2 || postTransitPath.size() < 2) {
				throw new IllegalArgumentException(
					"Transit firstLegPath/postTransitPath must each have at least 2 waypoints");
			}
			if (routeId == null || routeId.isBlank()
				|| boardingStopId == null || boardingStopId.isBlank()
				|| alightingStopId == null || alightingStopId.isBlank()) {
				throw new IllegalArgumentException(
					"Transit routeId/boardingStopId/alightingStopId must be non-blank");
			}
			if (firstLegDurationGameMinutes < 0L || postTransitDurationGameMinutes < 0L) {
				throw new IllegalArgumentException(
					"Transit leg durations must be non-negative");
			}
		}
	}

	record NoPath() implements RoutePlan {
		public static final NoPath INSTANCE = new NoPath();
	}
}

