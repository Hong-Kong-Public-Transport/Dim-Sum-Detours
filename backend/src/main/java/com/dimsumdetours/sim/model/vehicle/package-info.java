/**
 * Phase-12 vehicle entity model. Sealed {@link com.dimsumdetours.sim.model.vehicle.Vehicle}
 * hierarchy carrying inventory + path between an origin and a destination, advanced once
 * per simulation tick. {@link com.dimsumdetours.sim.model.vehicle.Robot} is the only
 * concrete kind today; {@code Bus} and {@code Train} land in later phases when the GTFS
 * schedule + OSM street pathfinding are wired in.
 *
 * <p>Framework-agnostic — no Spring / Jackson / JPA imports here, same rule as the rest
 * of the {@code sim/} package.
 */
@NullMarked
package com.dimsumdetours.sim.model.vehicle;

import org.jspecify.annotations.NullMarked;

