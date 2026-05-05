package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.LatLon;
import com.dimsumdetours.sim.model.vehicle.VehicleHandoff;

import java.util.List;

/**
 * Phase-16: a planner-built multi-leg shipment plan. The first leg is described
 * inline; each subsequent leg lives in {@code firstLegHandoff}'s recursive
 * {@link VehicleHandoff#nextHandoff()} chain. {@link GameState#spawnPlannedFirstLeg}
 * consumes a chain by spawning a robot for {@code firstLegPath} carrying
 * {@code firstLegHandoff} as its handoff, so on first-leg arrival the engine
 * automatically chains into a bus, then back into a robot for the final walk.
 *
 * <p>The plan is purely a description — no live UUIDs, no live cargo. The
 * dispatcher attaches those when it actually fires the spawn.
 */
public record VehicleChain(
	List<LatLon> firstLegPath,
	long firstLegDurationGameMinutes,
	VehicleHandoff firstLegHandoff
) {

	public VehicleChain {
		firstLegPath = List.copyOf(firstLegPath);
		if (firstLegPath.size() < 2) {
			throw new IllegalArgumentException(
				"VehicleChain firstLegPath must have ≥ 2 waypoints, got " + firstLegPath.size());
		}
		if (firstLegDurationGameMinutes <= 0) {
			throw new IllegalArgumentException(
				"VehicleChain firstLegDuration must be positive, got " + firstLegDurationGameMinutes);
		}
	}
}

