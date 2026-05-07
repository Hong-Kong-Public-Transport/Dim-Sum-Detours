package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small autonomous courier. Walks streets at
 * {@link GameConstants#ROBOT_METERS_PER_GAME_MINUTE} (≈ 10 km/h, casual
 * biking pace). One robot carries one ingredient kind at a time.
 *
 * <p>Phase-21: a robot may carry an optional {@link TransitBoarding
 * boarding} that flips it into "first-mile to a transit stop" mode.
 * On arrival at the stop the boarding state machine in
 * {@code GameState.advanceVehicles} drains the cargo into a
 * {@link WaitingCargo} keyed by {@code (boardingStopId, routeId)} —
 * the cargo never reaches the planned destination directly. Replaces
 * the recursive {@code VehicleHandoff} chain that the legacy planner
 * used to splice into a robot/bus/robot triple.
 *
 * <p>{@code boarding == null} means a regular delivery — the robot
 * applies its cargo to {@link #destinationBuildingId()} on arrival
 * (input stockpile for factories, fulfil/spoil for restaurant orders).
 * That covers both direct shipments (no transit) and connecting-leg
 * robots spawned by the alighting scan after a manifest unloads.
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
	@Nullable TransitBoarding boarding
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
	public double metersPerGameMinute() {
		return GameConstants.ROBOT_METERS_PER_GAME_MINUTE;
	}

	/** Has the simulation clock crossed this robot's arrival deadline? */
	public boolean hasArrived(long currentGameMinutes) {
		return currentGameMinutes >= arrivesAtGameMinutes;
	}
}

