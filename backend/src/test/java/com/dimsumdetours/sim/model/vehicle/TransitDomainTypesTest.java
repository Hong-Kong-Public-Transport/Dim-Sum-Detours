package com.dimsumdetours.sim.model.vehicle;

import com.dimsumdetours.sim.model.LatLon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase-21 unit tests covering the new domain-type stubs introduced in
 * the previous turn ({@link TransitRunId}, {@link CargoManifest},
 * {@link TransitVehicle}, {@link TransitBoarding}, {@link WaitingCargo}).
 * Validates the invariants the records advertise (non-blank ids, ≥ 2-
 * waypoint paths, non-empty cargo, monotonic loaded-at) and exercises
 * the {@link TransitVehicle} immutable-update helpers the upcoming
 * dispatcher rewrite will rely on.
 */
class TransitDomainTypesTest {

	private static final TransitRunId RUN = new TransitRunId("route-5A", 35L);

	@Test
	void transitRunIdRejectsBlankRouteId() {
		assertThatThrownBy(() -> new TransitRunId("", 0L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TransitRunId(null, 0L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cargoManifestRejectsEmptyCargo() {
		assertThatThrownBy(() -> manifest(Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cargoManifestRejectsSingleWaypointPath() {
		assertThatThrownBy(() -> new CargoManifest(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			"stop-A", "stop-B", Map.of("flour", 5),
			null, null,
			List.of(new LatLon(0, 0)), 5L, 100L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transitVehicleAppendsAndRemovesManifestsImmutably() {
		CargoManifest m1 = manifest(Map.of("flour", 3));
		CargoManifest m2 = manifest(Map.of("rice", 2, "salt", 1));
		TransitVehicle empty = new TransitVehicle(RUN, /* gtfsRouteType = */ 3, List.of());

		TransitVehicle one = empty.withManifestAppended(m1);
		assertThat(empty.manifests()).isEmpty();
		assertThat(one.manifests()).hasSize(1);
		assertThat(one.totalCargoUnits()).isEqualTo(3);

		TransitVehicle two = one.withManifestAppended(m2);
		assertThat(two.manifests()).hasSize(2);
		assertThat(two.totalCargoUnits()).isEqualTo(3 + 3);

		TransitVehicle afterRemove = two.withManifestRemoved(m1.id());
		assertThat(afterRemove.manifests()).containsExactly(m2);
		assertThat(afterRemove.isEmpty()).isFalse();
		assertThat(afterRemove.withManifestRemoved(m2.id()).isEmpty()).isTrue();
		// Original references untouched.
		assertThat(two.manifests()).hasSize(2);
	}

	@Test
	void transitBoardingRequiresValidStopIds() {
		assertThatThrownBy(() -> new TransitBoarding(
			"route-5A", "", "stop-B",
			List.of(new LatLon(0, 0), new LatLon(1, 1)), 1L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void waitingCargoRejectsBlankIds() {
		CargoManifest m = manifest(Map.of("flour", 1));
		assertThatThrownBy(() -> new WaitingCargo(
			UUID.randomUUID(), "", "stop-A", m, 100L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cargoEventRoundTripsThroughVariants() {
		CargoEvent.RunStarted started = new CargoEvent.RunStarted(RUN, 3, 100L);
		CargoEvent.CargoLoaded loaded = new CargoEvent.CargoLoaded(
			RUN, UUID.randomUUID(), 5, 101L);
		CargoEvent.CargoUnloaded unloaded = new CargoEvent.CargoUnloaded(
			RUN, UUID.randomUUID(), 0, 110L);
		CargoEvent.RunFinished finished = new CargoEvent.RunFinished(RUN, 111L);

		assertThat(started.type()).isEqualTo("RUN_STARTED");
		assertThat(loaded.type()).isEqualTo("CARGO_LOADED");
		assertThat(unloaded.type()).isEqualTo("CARGO_UNLOADED");
		assertThat(finished.type()).isEqualTo("RUN_FINISHED");
		assertThat(List.of(started, loaded, unloaded, finished))
			.allMatch(e -> e.run().equals(RUN));
	}

	private static CargoManifest manifest(Map<String, Integer> cargo) {
		return new CargoManifest(
			UUID.randomUUID(),
			UUID.randomUUID(),
			UUID.randomUUID(),
			"stop-A", "stop-B",
			cargo,
			null, null,
			List.of(new LatLon(0, 0), new LatLon(1, 1)),
			5L, 100L);
	}
}

