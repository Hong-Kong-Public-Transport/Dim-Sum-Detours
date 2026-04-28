package com.dimsumdetours;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dim Sum Detours backend entry point.
 *
 * <p>Architecture notes:
 * <ul>
 *   <li>Web layer is reactive (Spring WebFlux).</li>
 *   <li>Persistence is blocking JPA — repository calls MUST be wrapped in
 *       {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 *       so the event loop is never blocked.</li>
 *   <li>The {@code com.dimsumdetours.sim} package is framework-agnostic. Do NOT add Spring
 *       imports there — it is portable to other runtimes (e.g. a future Unity port).</li>
 * </ul>
 */
@SpringBootApplication
public class DimSumDetoursApplication {

	public static void main(String[] args) {
		SpringApplication.run(DimSumDetoursApplication.class, args);
	}
}
