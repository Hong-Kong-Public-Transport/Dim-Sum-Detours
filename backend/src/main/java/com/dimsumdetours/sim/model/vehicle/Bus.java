package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-16 GTFS-multi-leg carrier. A bus replaces a {@link Robot} for the long-haul
 * middle leg of a chained shipment: a robot walks the cargo to a boarding stop, hands
 * off to this bus, the bus rides the GTFS-scheduled trip to the alighting stop, and
 * its handoff respawns a fresh robot to walk the final leg. Cargo flows through every
 * leg unchanged — the bus is purely a courier kind, not a separate inventory.
 *
 * <p>Speed is GTFS-derived (path-length divided by scheduled duration) rather than the
 * fixed 170 m/min that robots use, so the on-screen marker matches the published
 * schedule even when the route detours through congested geometry.
 *
 * <p>{@code tripId} / {@code routeId} are carried for SSE attribution ("rode KMB 5A").
 * They are nullable because tests construct buses outside the planner, but the planner
 * always populates both in production.
 */
public record Bus(
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
	@Nullable VehicleHandoff handoff,
	@Nullable String tripId,
	@Nullable String routeId
) implements Vehicle {

	public Bus {
		cargo = Map.copyOf(cargo);
		path = List.copyOf(path);
		if (path.size() < 2) {
			throw new IllegalArgumentException(
				"Bus path must have at least 2 waypoints, got " + path.size());
		}
		if (cargo.isEmpty()) {
			throw new IllegalArgumentException("Bus cargo cannot be empty");
		}
		for (Map.Entry<String, Integer> entry : cargo.entrySet()) {
			if (entry.getValue() == null || entry.getValue() <= 0) {
				throw new IllegalArgumentException(
					"Bus cargo quantities must be positive, got "
						+ entry.getKey() + "=" + entry.getValue());
			}
		}
		if (departsAtGameMinutes < spawnedAtGameMinutes
			|| arrivesAtGameMinutes <= departsAtGameMinutes) {
			throw new IllegalArgumentException(
				"Bus timing must satisfy spawnedAt <= departsAt < arrivesAt, got "
					+ spawnedAtGameMinutes + " / " + departsAtGameMinutes
					+ " / " + arrivesAtGameMinutes);
		}
	}

	@Override
	public VehicleKind kind() {
		return VehicleKind.BUS;
	}

	@Override
	public double metersPerGameMinute() {
		// Speed reflects the moving phase only; the loading window doesn't slow
		// the bus down, it just delays its departure.
		double duration = Math.max(1.0, arrivesAtGameMinutes - departsAtGameMinutes);
		double total = 0.0;
		for (int i = 1; i < path.size(); i++) {
			LatLon a = path.get(i - 1);
			LatLon b = path.get(i);
			total += haversineMetres(a.lat(), a.lon(), b.lat(), b.lon());
		}
		return total / duration;
	}

	public boolean hasArrived(long currentGameMinutes) {
		return currentGameMinutes >= arrivesAtGameMinutes;
	}

	private static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
		double earthRadiusMetres = 6_371_000.0;
		double phi1 = Math.toRadians(lat1);
		double phi2 = Math.toRadians(lat2);
		double deltaPhi = Math.toRadians(lat2 - lat1);
		double deltaLambda = Math.toRadians(lon2 - lon1);
		double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
			+ Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
		return 2 * earthRadiusMetres * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}
}

