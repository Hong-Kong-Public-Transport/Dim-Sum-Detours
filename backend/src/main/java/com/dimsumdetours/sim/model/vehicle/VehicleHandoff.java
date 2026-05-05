package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Phase-16: a "what comes next" instruction tucked onto a {@link Vehicle} so the
 * arrival branch in {@code GameState.advanceVehicles} knows to chain into a new
 * vehicle instead of applying cargo to the destination building. Used to express
 * a multi-leg plan as a recursive linked-list of legs, with cargo flowing through
 * verbatim:
 *
 * <pre>
 *   Robot(path = source→boardingStop, handoff = HANDOFF_TO_BUS)
 *       └── Bus(path = boardingStop→alightingStop, handoff = HANDOFF_TO_ROBOT)
 *               └── Robot(path = alightingStop→destination, handoff = null)
 * </pre>
 *
 * <p>The terminal vehicle in the chain has {@code handoff == null} and applies
 * cargo normally on arrival. Bus and train legs carry their GTFS {@code tripId}
 * so the SSE stream can surface "rode route 5A" attribution; robot legs leave
 * those nullable.
 */
public record VehicleHandoff(
	VehicleKind nextKind,
	List<LatLon> nextPath,
	long nextDurationGameMinutes,
	@Nullable String tripId,
	@Nullable String routeId,
	@Nullable VehicleHandoff nextHandoff
) {

	public VehicleHandoff {
		nextPath = List.copyOf(nextPath);
		if (nextPath.size() < 2) {
			throw new IllegalArgumentException(
				"VehicleHandoff path must have at least 2 waypoints, got " + nextPath.size());
		}
		if (nextDurationGameMinutes <= 0) {
			throw new IllegalArgumentException(
				"VehicleHandoff duration must be positive, got " + nextDurationGameMinutes);
		}
		if (nextKind == VehicleKind.BUS && tripId == null) {
			throw new IllegalArgumentException("BUS handoff requires a tripId");
		}
	}
}

