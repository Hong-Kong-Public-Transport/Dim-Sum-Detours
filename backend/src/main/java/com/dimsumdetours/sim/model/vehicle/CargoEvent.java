package com.dimsumdetours.sim.model.vehicle;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Phase-21 stub: cargo lifecycle events broadcast over the unified SSE
 * stream so the frontend can scale ambient transit sprites by manifest
 * count without needing to allocate a backend {@link TransitVehicle}
 * for every run.
 *
 * <ul>
 *   <li>{@link RunStarted} — a {@link TransitRunId} just acquired its
 *       first manifest; the frontend should track it as "carrying
 *       cargo".</li>
 *   <li>{@link CargoLoaded} — a manifest just boarded an existing run
 *       (run total grew).</li>
 *   <li>{@link CargoUnloaded} — a manifest just alighted (run total
 *       shrank); the connecting robot's spawn is broadcast as a
 *       regular {@link VehicleEvent.Spawned}.</li>
 *   <li>{@link RunFinished} — the run lost its last manifest; frontend
 *       returns the sprite to its ambient (no-cargo) appearance.</li>
 * </ul>
 *
 * <p>No callers yet — wired into {@code SimulationEngine} +
 * {@code ServerEvent.Cargo} variant in Phase 21.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	visible = true
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = CargoEvent.RunStarted.class, name = "RUN_STARTED"),
	@JsonSubTypes.Type(value = CargoEvent.CargoLoaded.class, name = "CARGO_LOADED"),
	@JsonSubTypes.Type(value = CargoEvent.CargoUnloaded.class, name = "CARGO_UNLOADED"),
	@JsonSubTypes.Type(value = CargoEvent.RunFinished.class, name = "RUN_FINISHED")
})
public sealed interface CargoEvent {

	String type();

	long gameMinutes();

	TransitRunId run();

	record RunStarted(TransitRunId run, int gtfsRouteType, long gameMinutes)
		implements CargoEvent {
		@Override public String type() { return "RUN_STARTED"; }
	}

	record CargoLoaded(TransitRunId run, UUID manifestId, int totalCargoUnits, long gameMinutes)
		implements CargoEvent {
		@Override public String type() { return "CARGO_LOADED"; }
	}

	record CargoUnloaded(TransitRunId run, UUID manifestId, int totalCargoUnits, long gameMinutes)
		implements CargoEvent {
		@Override public String type() { return "CARGO_UNLOADED"; }
	}

	record RunFinished(TransitRunId run, long gameMinutes)
		implements CargoEvent {
		@Override public String type() { return "RUN_FINISHED"; }
	}
}

