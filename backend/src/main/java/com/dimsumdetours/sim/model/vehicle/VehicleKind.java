package com.dimsumdetours.sim.model.vehicle;

/**
 * Discriminator for the sealed {@link Vehicle} hierarchy. Today only {@link #ROBOT} is
 * implemented; {@code BUS} and {@code TRAIN} are reserved for the GTFS-driven vehicles
 * a later phase will add. The enum stays here (rather than as a {@code kind()} string on
 * the DTO) so the frontend gets a closed set of values to switch on.
 */
public enum VehicleKind {
	ROBOT
}

