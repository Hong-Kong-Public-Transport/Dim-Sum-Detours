package com.dimsumdetours.api;

import com.dimsumdetours.gtfs.TransitSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase-18: exposes the {@link TransitSnapshotService}'s pre-built feed snapshot
 * (stops + route shapes + per-route ordered stop sequences) so the frontend can
 * render a static stops layer + animate ambient transit markers along the
 * shapes without any per-tick server traffic.
 *
 * <p>Returns 503 if no GTFS feed is loaded.
 */
@RestController
@RequestMapping(path = "/api/transit", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TransitController {

	private final TransitSnapshotService snapshotService;

	@GetMapping("/snapshot")
	public ResponseEntity<TransitSnapshotService.Snapshot> snapshot() {
		return snapshotService.getSnapshot()
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.status(503).build());
	}
}

