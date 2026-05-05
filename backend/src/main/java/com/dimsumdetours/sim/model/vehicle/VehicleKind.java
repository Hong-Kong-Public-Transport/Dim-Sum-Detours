package com.dimsumdetours.sim.model.vehicle;

/**
 * Discriminator for the sealed {@link Vehicle} hierarchy. {@link #ROBOT} is the
 * autonomous-courier base case (≤ 5 km legs, casual-biking speed); {@link #BUS}
 * is the GTFS-driven multi-leg carrier added in Phase 16 — robots board it at a
 * boarding stop and a fresh robot respawns at the alighting stop. {@code TRAIN}
 * is reserved for a later GTFS-rail pass; it shares the bus state machine with
 * route-tier-specific tuning (capacity, fare, glyph).
 */
public enum VehicleKind {
	ROBOT,
	BUS
}

