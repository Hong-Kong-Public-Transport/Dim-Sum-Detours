package com.dimsumdetours.sim.model;

/**
 * A geodesic point. Used for vehicle path waypoints (Phase-12 robot model). Validated
 * for finite, in-range coordinates so an upstream {@code NaN} doesn't poison a
 * pathfinding tick.
 */
public record LatLon(double lat, double lon) {

	public LatLon {
		if (!Double.isFinite(lat) || !Double.isFinite(lon)
			|| lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
			throw new IllegalArgumentException(
				"LatLon out of range: lat=" + lat + ", lon=" + lon);
		}
	}
}

