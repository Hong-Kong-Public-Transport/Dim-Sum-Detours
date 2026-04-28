package com.dimsumdetours.api;

import com.dimsumdetours.gtfs.GtfsLoader;
import lombok.RequiredArgsConstructor;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Reactive endpoints for inspecting GTFS feeds available in the configured directory.
 *
 * <p>Parsing a feed is blocking; we offload it onto {@code Schedulers.boundedElastic()}.
 */
@RestController
@RequestMapping(path = "/api/gtfs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GtfsController {

	private final GtfsLoader loader;


	@GetMapping("/feeds")
	public Flux<String> listFeeds() {
		return Mono.fromCallable(loader::listFeeds)
			.subscribeOn(Schedulers.boundedElastic())
			.flatMapMany(Flux::fromIterable);
	}

	@GetMapping("/feeds/{name}/summary")
	public Mono<FeedSummary> summary(@PathVariable String name) {
		return Mono.fromCallable(() -> {
				GtfsRelationalDaoImpl gtfsData = loader.loadFeed(name);
				return new FeedSummary(
					name,
					gtfsData.getAllAgencies().stream().map(Agency::getName).toList(),
					gtfsData.getAllStops().size(),
					gtfsData.getAllRoutes().size(),
					gtfsData.getAllTrips().size());
			})
			.subscribeOn(Schedulers.boundedElastic());
	}

	public record FeedSummary(
		String name,
		List<String> agencies,
		int stops,
		int routes,
		int trips
	) {
	}
}
