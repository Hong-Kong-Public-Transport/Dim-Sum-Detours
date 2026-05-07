package com.dimsumdetours.sim.model.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase-21 stub: a backend-side handle for a transit run that
 * <em>currently</em> carries cargo. Crucially this is <strong>not</strong>
 * a {@link Vehicle} — ambient transit vehicles are purely client-side
 * sprites (their position is a deterministic function of the GTFS
 * snapshot + game clock), so the server only allocates a
 * {@code TransitVehicle} when at least one {@link CargoManifest} boards
 * a specific {@link TransitRunId}, and deallocates when the last
 * manifest unloads.
 *
 * <p>{@code gtfsRouteType} is plumbed through purely for the icon —
 * dispatch / boarding / capacity behaviour is identical across all
 * modes per Phase-20 mode unification.
 *
 * <p>No callers yet — wired into {@code GameState.liveTransitVehicles}
 * in Phase 21.
 */
public record TransitVehicle(
	TransitRunId run,
	int gtfsRouteType,
	List<CargoManifest> manifests
) {

	public TransitVehicle {
		manifests = List.copyOf(manifests);
	}

	public TransitVehicle withManifests(List<CargoManifest> next) {
		return new TransitVehicle(run, gtfsRouteType, next);
	}

	public TransitVehicle withManifestAppended(CargoManifest manifest) {
		List<CargoManifest> next = new ArrayList<>(manifests.size() + 1);
		next.addAll(manifests);
		next.add(manifest);
		return withManifests(next);
	}

	public TransitVehicle withManifestRemoved(java.util.UUID manifestId) {
		List<CargoManifest> next = new ArrayList<>(Math.max(0, manifests.size() - 1));
		for (CargoManifest m : manifests) {
			if (!m.id().equals(manifestId)) {
				next.add(m);
			}
		}
		return withManifests(next);
	}

	public boolean isEmpty() {
		return manifests.isEmpty();
	}

	/** Total cargo units across every manifest aboard. Frontend uses this
	 * to scale the ambient sprite (see DISPATCH.md "Rendering"). */
	public int totalCargoUnits() {
		int sum = 0;
		for (CargoManifest m : manifests) {
			for (Integer qty : m.cargo().values()) {
				sum += qty;
			}
		}
		return sum;
	}
}

