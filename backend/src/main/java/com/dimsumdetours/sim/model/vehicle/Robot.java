package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small autonomous courier. Walks streets at {@link GameConstants#ROBOT_METERS_PER_GAME_MINUTE}
 * (≈ 10 km/h, casual biking pace — slower than a bus, faster than the previous walker
 * model). One robot carries one ingredient kind at a time.
 *
 * <p>Phase-16: a robot may carry an optional {@link VehicleHandoff handoff} that fires
 * when it arrives at its (interim) destination — typically a GTFS boarding stop. The
 * handoff causes the engine to despawn this robot and spawn a {@link Bus} carrying the
 * same cargo, so the cargo flows through the multi-leg plan without ever leaving
 * server state.
 */
public record Robot(
	UUID id,
	UUID sourceBuildingId,
	UUID destinationBuildingId,
	Map<String, Integer> cargo,
	List<LatLon> path,
	long spawnedAtGameMinutes,
	long departsAtGameMinutes,
	long arrivesAtGameMinutes,
	@Nullable UUID orderId,
	@Nullable Long spoilageDeadlineGameMinutes,
	@Nullable VehicleHandoff handoff
) implements Vehicle {

	public Robot {
		cargo = Map.copyOf(cargo);
		path = List.copyOf(path);
		if (path.size() < 2) {
			throw new IllegalArgumentException(
				"Robot path must have at least 2 waypoints, got " + path.size());
		}
		if (cargo.isEmpty()) {
			throw new IllegalArgumentException("Robot cargo cannot be empty");
		}
		for (Map.Entry<String, Integer> entry : cargo.entrySet()) {
			if (entry.getValue() == null || entry.getValue() <= 0) {
				throw new IllegalArgumentException(
					"Robot cargo quantities must be positive, got "
						+ entry.getKey() + "=" + entry.getValue());
			}
		}
		if (departsAtGameMinutes < spawnedAtGameMinutes
			|| arrivesAtGameMinutes < departsAtGameMinutes) {
			throw new IllegalArgumentException(
				"Robot timing must satisfy spawnedAt <= departsAt <= arrivesAt, got "
					+ spawnedAtGameMinutes + " / " + departsAtGameMinutes
					+ " / " + arrivesAtGameMinutes);
		}
	}

	@Override
	public VehicleKind kind() {
		return VehicleKind.ROBOT;
	}

	@Override
	public double metersPerGameMinute() {
		return GameConstants.ROBOT_METERS_PER_GAME_MINUTE;
	}

	/** Has the simulation clock crossed this robot's arrival deadline? */
	public boolean hasArrived(long currentGameMinutes) {
		return currentGameMinutes >= arrivesAtGameMinutes;
	}
}

