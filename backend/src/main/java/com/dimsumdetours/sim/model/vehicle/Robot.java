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
 * model). One robot carries one ingredient kind at a time; multi-ingredient cargo and
 * larger {@code Bus} / {@code Train} subtypes are deferred to a future phase.
 */
public record Robot(
	UUID id,
	UUID sourceBuildingId,
	UUID destinationBuildingId,
	Map<String, Integer> cargo,
	List<LatLon> path,
	long spawnedAtGameMinutes,
	long arrivesAtGameMinutes,
	@Nullable UUID orderId,
	@Nullable Long spoilageDeadlineGameMinutes
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
		if (arrivesAtGameMinutes < spawnedAtGameMinutes) {
			throw new IllegalArgumentException(
				"arrivesAt < spawnedAt (" + arrivesAtGameMinutes
					+ " < " + spawnedAtGameMinutes + ")");
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

