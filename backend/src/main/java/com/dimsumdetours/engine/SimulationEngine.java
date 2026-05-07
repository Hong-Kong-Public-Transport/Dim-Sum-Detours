package com.dimsumdetours.engine;

import com.dimsumdetours.api.GameController;
import com.dimsumdetours.api.GameEvent;
import com.dimsumdetours.api.ServerEvent;
import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.gtfs.TransitSnapshotService;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.Milestone;
import com.dimsumdetours.sim.model.MilestoneEvent;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.vehicle.CargoEvent;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.MilestoneTracker;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 4 simulation engine. Owns the real-time → game-time conversion and ticks the
 * {@link GameState} clock at {@link GameConstants#SIM_TICK_MILLIS} intervals.
 *
 * <p>Lives <strong>outside</strong> {@code sim/} so the simulation core stays Spring-free
 * (per the README rule). The engine itself is a thin Spring wrapper over
 * {@link GameState#advanceClock(long)} and a multicast {@link Sinks.Many} that broadcasts
 * the latest snapshot to any SSE subscribers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationEngine {

	private final GameState gameState;
	private final OrderGenerator orderGenerator;
	private final VehicleDispatcher vehicleDispatcher;
	private final ContentRegistry contentRegistry;
	/** Phase-21: route-type lookup for the {@code RUN_STARTED} cargo event.
	 * {@link GameState} is Spring-free so it can't reach into the GTFS feed
	 * itself — the engine resolves {@code gtfsRouteType} here just before
	 * publishing onto the SSE sink. */
	private final TransitSnapshotService transitSnapshotService;

	private final Sinks.Many<GameState.ClockSnapshot> clockSink =
		Sinks.many().multicast().onBackpressureBuffer(1, false);

	/** Phase-6 multicast of order lifecycle events (enqueued / fulfilled / expired). */
	private final Sinks.Many<OrderEvent> orderSink =
		Sinks.many().multicast().onBackpressureBuffer(16, false);

	/** Phase-8 task 7: multicast of milestone unlock events. Subscribers are the frontend
	 * milestone-toast service plus any analytics hook a future phase wants to bolt on. */
	private final Sinks.Many<MilestoneEvent> milestoneSink =
		Sinks.many().multicast().onBackpressureBuffer(8, false);

	/** Phase-12: vehicle lifecycle stream. Spawned + Arrived events; no per-tick "moved"
	 * events — the frontend interpolates locally from spawn time + path + speed. */
	private final Sinks.Many<VehicleEvent> vehicleSink =
		Sinks.many().multicast().onBackpressureBuffer(64, false);

	/** Phase-19: catch-all events stream — wallet mutations, building state
	 * changes, restaurant closures, world resets. See
	 * {@code docs/NETWORKING.md} and {@link GameEvent} for the protocol. */
	private final Sinks.Many<GameEvent> gameEventSink =
		Sinks.many().multicast().onBackpressureBuffer(64, false);

	/** Phase-21: cargo lifecycle stream — {@code RUN_STARTED} /
	 * {@code CARGO_LOADED} / {@code CARGO_UNLOADED} / {@code RUN_FINISHED}.
	 * Lets the frontend scale ambient transit sprites by manifest count
	 * without needing a backend {@code TransitVehicle} object on the
	 * client side. Emitted from the boarding / alighting scans inside
	 * {@link GameState#advanceVehicles(long)}; surfaced here on the
	 * unified {@code /api/game/stream} as {@link ServerEvent.Cargo}. */
	private final Sinks.Many<CargoEvent> cargoEventSink =
		Sinks.many().multicast().onBackpressureBuffer(64, false);

	/** Phase-19: last-known wallet balance. Diffed against post-tick balance to
	 * synthesise {@link GameEvent.BalanceChanged} events without each mutation
	 * site having to publish manually. {@link Long#MIN_VALUE} primes the
	 * comparison so the very first tick doesn't emit a phantom delta. */
	private long lastKnownBalance = Long.MIN_VALUE;

	/** Phase-19: last-emitted snapshot per building. Diffed against the
	 * post-tick state to synthesise {@link GameEvent.BuildingStateChanged}
	 * events for any field that changed. Maps building id → DTO. Cleared on
	 * world reset. */
	private final Map<UUID, GameController.BuildingDto> lastBuildingDtos = new HashMap<>();

	/** Phase-19: restaurants for which we've already emitted {@link
	 * GameEvent.RestaurantClosed}. Closure is one-way today, so re-emission
	 * is silent. */
	private final Set<UUID> closedRestaurantsAnnounced = new HashSet<>();

	/** Phase-19: last-known {@link GameState#worldEpoch()}. A bump means the
	 * world was reset; we drain our diff caches and emit {@link
	 * GameEvent.WorldReset}. */
	private long lastKnownEpoch = Long.MIN_VALUE;

	private final MilestoneTracker milestoneTracker = new MilestoneTracker();

	private @org.jspecify.annotations.Nullable Disposable tickSubscription;

	/** Carry-over fractional minutes between ticks so the clock stays integer. */
	private double minuteAccumulator = 0.0;

	/** Phase-14: last game-minute on which the milestone evaluator ran. The evaluator
	 * is idempotent within a game-minute — none of its predicates can flip without the
	 * clock advancing — so skipping it on duplicate-minute ticks (paused, or fractional
	 * accumulator hasn't crossed a boundary yet) drops a chunk of per-tick work. */
	private long lastEvaluatedGameMinute = Long.MIN_VALUE;

	/**
	 * Start the simulation tick loop after Spring has finished wiring AND
	 * {@link com.dimsumdetours.content.ContentLoader} has populated the registry.
	 *
	 * <p>{@link Order} is {@link Ordered#LOWEST_PRECEDENCE} so the loader (which
	 * uses the default precedence) runs first; otherwise an early tick can ask
	 * {@link OrderGenerator} / {@link VehicleDispatcher} for content that's not
	 * loaded yet.
	 */
	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.LOWEST_PRECEDENCE)
	void start() {
		if (tickSubscription != null) {
			return;
		}
		log.info("Starting simulation engine: tick {} ms, 1× = {} game-min/real-sec",
			GameConstants.SIM_TICK_MILLIS, GameConstants.GAME_MINUTES_PER_REAL_SECOND_AT_1X);
		tickSubscription = Flux.interval(Duration.ofMillis(GameConstants.SIM_TICK_MILLIS))
			.subscribeOn(Schedulers.parallel())
			.subscribe(unused -> tick());
	}

	@PreDestroy
	void stop() {
		if (tickSubscription != null) {
			tickSubscription.dispose();
			tickSubscription = null;
		}
		// Phase-19 lifecycle: complete every multicast sink so SSE subscribers
		// get a clean termination signal on context shutdown (otherwise reactive
		// tests that restart the context within one JVM see "Sink already
		// returning FAIL_TERMINATED" warnings on the second boot).
		clockSink.tryEmitComplete();
		orderSink.tryEmitComplete();
		milestoneSink.tryEmitComplete();
		vehicleSink.tryEmitComplete();
		gameEventSink.tryEmitComplete();
		cargoEventSink.tryEmitComplete();
	}

	private void tick() {
		try {
			GameState.ClockSnapshot before = gameState.getClockSnapshot();
			boolean simAdvancing = before.playing() && before.speed() > 0;
			if (simAdvancing) {
				double tickSeconds = GameConstants.SIM_TICK_MILLIS / 1000.0;
				double deltaMinutes = before.speed()
					* GameConstants.GAME_MINUTES_PER_REAL_SECOND_AT_1X
					* tickSeconds;
				minuteAccumulator += deltaMinutes;
				long whole = (long) minuteAccumulator;
				if (whole > 0) {
					minuteAccumulator -= whole;
					gameState.advanceClock(whole);
				}
			}
			GameState.ClockSnapshot after = gameState.getClockSnapshot();
			// Phase-21 fix: only run the per-tick simulation work while the clock
			// is actually advancing. Previously every helper below ran even when
			// paused, which (a) flooded the dispatcher log with retry chatter on
			// orders the player had no opportunity to fulfil and (b) let
			// production / orders / arrivals proceed against a frozen wall clock.
			// State-change emissions (clock SSE throttle + diff-and-emit) still
			// run unconditionally so pause/resume/reset echoes reach the UI.
			if (simAdvancing) {
				// Phase-8: roll forward farm/factory production cycles. Cheap walk over all
				// buildings; the frontend reads the post-tick fields off the next clock SSE frame.
				gameState.advanceProduction();
				// Drain expired orders once per tick; each gets pushed onto the order SSE stream.
				for (GameState.ExpiredOrderEvent expired : gameState.expirePendingOrders()) {
					orderSink.tryEmitNext(new OrderEvent.Expired(
						expired.order().id(),
						expired.order().restaurantId(),
						expired.newReputation(),
						after.gameMinutes()));
				}
				// Phase-7 daily upkeep: if the clock rolled into a new game-day, deduct upkeep for
				// every owned building. Logged but not pushed onto a stream — the wallet refresh
				// driven by the next clock SSE frame is enough for the player to notice.
				long upkeep = gameState.applyDailyUpkeepIfDayChanged();
				if (upkeep > 0L) {
					log.debug("Daily upkeep deducted: {}", upkeep);
				}
				// Phase-7: procedural demand. Emit at most one new order per tick.
				orderGenerator.maybeGenerate(after.gameMinutes()).ifPresent(orderSink::tryEmitNext);
				// Phase-12: dispatch robots for any pending order / under-stocked factory, then
				// advance the in-flight ones. Spawned events go on the vehicle stream; arrival
				// events fan out to both the vehicle stream AND the order stream (so the
				// frontend's restaurant drawers update without having to reconcile two feeds).
				for (VehicleEvent.Spawned spawned : vehicleDispatcher.dispatch()) {
					vehicleSink.tryEmitNext(spawned);
				}
				GameState.ArrivalBatch arrivals = gameState.advanceVehicles(before.gameMinutes());
				for (VehicleEvent.Arrived arrived : arrivals.vehicleEvents()) {
					vehicleSink.tryEmitNext(arrived);
				}
				// Phase-16: chained handoffs surface the next-leg spawn through the same
				// vehicle stream so the frontend's marker layer sees a normal SPAWNED event.
				for (VehicleEvent.Spawned chainSpawn : arrivals.spawnEvents()) {
					vehicleSink.tryEmitNext(chainSpawn);
				}
				for (OrderEvent.Fulfilled fulfilled : arrivals.orderEvents()) {
					orderSink.tryEmitNext(fulfilled);
					milestoneTracker.recordFulfillment(after.gameMinutes(), true, "", "");
				}
				// Phase-21 close-out: misc vehicle events — today exclusively
				// {@link VehicleEvent.RobotArrivedAtStop} for first-mile robots
				// reaching their boarding stop. Without this fan-out the
				// frontend never received the despawn frame, so the original
				// waiting robot lingered on the map even though its cargo had
				// already flipped into the server-side WaitingCargo queue and
				// a fresh connecting robot had spawned at the alighting stop
				// and walked successfully to the destination.
				for (VehicleEvent miscEvent : arrivals.miscEvents()) {
					vehicleSink.tryEmitNext(miscEvent);
				}
				// Phase-21: cargo lifecycle events emitted by the boarding /
				// alighting transitions inside advanceVehicles. We resolve the
				// real GTFS route-type for RUN_STARTED here (the GameState core
				// is Spring-free and can't reach the snapshot service).
				for (CargoEvent cargoEvent : arrivals.cargoEvents()) {
					cargoEventSink.tryEmitNext(resolveRouteType(cargoEvent));
				}
				// Phase-8 task 7: per-tick milestone evaluator. Cheap when nothing's changed; the
				// tracker short-circuits each predicate by checking the unlocked-set first.
				// Phase-14: skip entirely when the game-minute hasn't advanced (paused, or the
				// fractional accumulator hasn't crossed a whole-minute boundary yet) — none of
				// the milestone predicates can flip without a clock tick.
				if (after.gameMinutes() != lastEvaluatedGameMinute) {
					lastEvaluatedGameMinute = after.gameMinutes();
					for (Milestone milestone : milestoneTracker.evaluate(gameState, java.util.Map.of(), after.gameMinutes())) {
						milestoneSink.tryEmitNext(new MilestoneEvent(milestone, after.gameMinutes()));
					}
				}
			}
			// Phase-13: throttle the clock SSE to ~1 Hz of wall-clock time regardless of
			// game speed. The frontend extrapolates the game-minute locally between
			// frames (ClockService.liveGameMinutes) and the Pixi robot layer lerps off
			// that, so flooding the wire with 10 frames-per-second of essentially
			// redundant snapshots only created jitter at high game speeds. State
			// transitions (pause/resume/setSpeed) bypass this throttle via
			// {@link #publishClockSnapshot} so the UI sees them immediately.
			long nowWallMs = System.currentTimeMillis();
			boolean stateChanged = lastEmittedSnapshot == null
				|| lastEmittedSnapshot.playing() != after.playing()
				|| lastEmittedSnapshot.speed() != after.speed()
				|| lastEmittedSnapshot.worldEpoch() != after.worldEpoch();
			if (stateChanged || nowWallMs - lastClockEmitWallMs >= CLOCK_SSE_INTERVAL_MS) {
				lastClockEmitWallMs = nowWallMs;
				lastEmittedSnapshot = after;
				clockSink.tryEmitNext(after);
			}

			// Phase-19: synthesise wallet + building diffs into GameEvents so the
			// frontend never has to poll. Diffing is cheap (a HashMap walk) and
			// makes every mutation site automatically push without sprinkling
			// publisher calls all over GameState.
			emitDiffedEvents(after);
		} catch (RuntimeException ex) {
			log.warn("Simulation tick failed: {}", ex.getMessage());
		}
	}

	/** Wall-clock interval between throttled clock SSE emissions. The frontend
	 * extrapolates between frames so this can be coarse without affecting visuals. */
	private static final long CLOCK_SSE_INTERVAL_MS = 1000L;

	/** Last wall-clock millisecond at which a clock snapshot was pushed onto the SSE
	 * sink. {@link #tick} consults this to throttle emissions to {@link #CLOCK_SSE_INTERVAL_MS}. */
	private long lastClockEmitWallMs = 0L;

	/** Last snapshot pushed onto the clock SSE sink. Used to detect playing/speed
	 * transitions so the throttle yields immediately when the player hits pause/resume
	 * or changes speed. */
	private GameState.@org.jspecify.annotations.Nullable ClockSnapshot lastEmittedSnapshot;

	/**
	 * Phase-13: force-push the current clock snapshot onto the SSE stream. Called from
	 * {@link com.dimsumdetours.api.ClockController} immediately after a pause / resume /
	 * speed change so the frontend re-anchors its lerp without waiting for the next
	 * throttled tick (which can be up to {@link #CLOCK_SSE_INTERVAL_MS} away).
	 */
	public void publishClockSnapshot() {
		GameState.ClockSnapshot snapshot = gameState.getClockSnapshot();
		lastClockEmitWallMs = System.currentTimeMillis();
		lastEmittedSnapshot = snapshot;
		clockSink.tryEmitNext(snapshot);
	}

	/** Live stream of clock snapshots, one per tick. Subscribed to by the SSE controller. */
	public Flux<GameState.ClockSnapshot> clockStream() {
		return clockSink.asFlux();
	}

	/**
	 * Live stream of restaurant-order lifecycle events. Subscribed to by the SSE controller
	 * and emitted from {@link com.dimsumdetours.api.GameController} on enqueue / fulfill, and
	 * from the engine itself on expiry.
	 */
	public Flux<OrderEvent> orderStream() {
		return orderSink.asFlux();
	}

	/** Push an event onto the order SSE stream. Called from the API after enqueue / fulfill. */
	public void publishOrderEvent(OrderEvent event) {
		orderSink.tryEmitNext(event);
	}

	/** Live stream of milestone unlock events (Phase-8 task 7). */
	public Flux<MilestoneEvent> milestoneStream() {
		return milestoneSink.asFlux();
	}

	/** Live stream of vehicle lifecycle events (Phase-12). */
	public Flux<VehicleEvent> vehicleStream() {
		return vehicleSink.asFlux();
	}

	/** Phase-19: live stream of catch-all state-change events (wallet,
	 * building state, restaurant closure, world reset). See
	 * {@link GameEvent} for the full event taxonomy. */
	public Flux<GameEvent> gameEventStream() {
		return gameEventSink.asFlux();
	}

	/** Phase-19: push a {@link GameEvent} onto the stream. Called from REST
	 * handlers (placement, demolition, refrigeration, recipe reorder) that
	 * mutate state synchronously and want the SSE echo to fire without
	 * waiting for the next sim tick. */
	public void publishGameEvent(GameEvent event) {
		gameEventSink.tryEmitNext(event);
	}

	/** Phase-21: live stream of cargo lifecycle events. */
	public Flux<CargoEvent> cargoEventStream() {
		return cargoEventSink.asFlux();
	}

	/** Phase-21: push a {@link CargoEvent} onto the cargo stream. Called
	 * from REST handlers that want to surface a cargo-stream side-effect
	 * synchronously without waiting for the next sim tick. The boarding
	 * state machine in {@link GameState#advanceVehicles(long)} emits
	 * directly via the per-tick fan-out in {@link #tick}. */
	public void publishCargoEvent(CargoEvent event) {
		cargoEventSink.tryEmitNext(event);
	}

	/** Phase-19 Phase-G: live stream of every server-emitted event multiplexed
	 * onto one channel. Built lazily on first subscribe via {@link Flux#merge}
	 * over the five domain-specific sinks; each downstream subscriber gets
	 * an independent merged view, so the legacy per-channel endpoints can
	 * still operate on their original sinks unchanged.
	 *
	 * <p>The frontend's {@code ServerStreamService} consumes this and fans
	 * out to {@link com.dimsumdetours.api.ServerEvent} variants —
	 * collapsing 5 EventSource connections into 1, well below the browser's
	 * 6-per-origin cap. */
	public Flux<ServerEvent> serverEventStream() {
		return Flux.merge(
			clockSink.asFlux().map(ServerEvent.Clock::new),
			vehicleSink.asFlux().map(ServerEvent.Vehicle::new),
			orderSink.asFlux().map(ServerEvent.Order::new),
			milestoneSink.asFlux().map(ServerEvent.Milestone::new),
			gameEventSink.asFlux().map(ServerEvent.Game::new),
			cargoEventSink.asFlux().map(ServerEvent.Cargo::new)
		);
	}

	/** Tracker handle so the API can serve the snapshot endpoint and feed fulfillments in. */
	public MilestoneTracker milestoneTracker() {
		return milestoneTracker;
	}

	// ─── Phase-19: diff-and-emit ──────────────────────────────────────────

	/**
	 * Diff post-tick state against the last-emitted snapshots and emit a
	 * {@link GameEvent} per change. Runs once per tick (cheap — HashMap
	 * walks). Replaces the pre-Phase-19 model where the frontend polled
	 * {@code /api/game/buildings} + {@code /api/game/balance} after every
	 * vehicle arrival.
	 */
	private void emitDiffedEvents(GameState.ClockSnapshot after) {
		long wallMs = System.currentTimeMillis();
		long epoch = after.worldEpoch();

		// World reset detection. A reset zeros the clock and bumps the epoch;
		// drain every diff cache so subsequent diffs don't emit phantom
		// "stockpile cleared" events for the post-reset world.
		if (lastKnownEpoch != Long.MIN_VALUE && epoch != lastKnownEpoch) {
			lastBuildingDtos.clear();
			closedRestaurantsAnnounced.clear();
			lastKnownBalance = Long.MIN_VALUE;
			gameEventSink.tryEmitNext(new GameEvent.WorldReset(
				after.gameMinutes(), wallMs, epoch, "reset"));
		}
		lastKnownEpoch = epoch;

		// Balance diff.
		long balance = gameState.getBalance().amount();
		if (lastKnownBalance != Long.MIN_VALUE && balance != lastKnownBalance) {
			gameEventSink.tryEmitNext(new GameEvent.BalanceChanged(
				balance, balance - lastKnownBalance, "tick",
				after.gameMinutes(), wallMs, epoch));
		}
		lastKnownBalance = balance;

		// Building diff. We only emit when a field that the frontend renders
		// actually changed — comparing the DTO records via {@link Object#equals}
		// captures producedUnits / cycle anchor / stockpile / stalled / closed /
		// reputation / fulfilledOrders without a hand-rolled per-field check.
		Set<UUID> liveIds = new HashSet<>();
		for (Building building : gameState.listBuildings()) {
			liveIds.add(building.id());
			GameController.BuildingDto dto = GameController.BuildingDto.from(building, contentRegistry);
			GameController.BuildingDto previous = lastBuildingDtos.get(building.id());
			if (previous == null || !Objects.equals(previous, dto)) {
				lastBuildingDtos.put(building.id(), dto);
				gameEventSink.tryEmitNext(new GameEvent.BuildingStateChanged(
					dto, after.gameMinutes(), wallMs, epoch));
			}
			// Restaurant closure transition (one-way). Surface the first time
			// we observe `closed == true`.
			if (building instanceof Restaurant restaurant
				&& restaurant.closed()
				&& closedRestaurantsAnnounced.add(restaurant.id())) {
				gameEventSink.tryEmitNext(new GameEvent.RestaurantClosed(
					restaurant.id(), restaurant.reputation(),
					after.gameMinutes(), wallMs, epoch));
			}
		}
		// Drop any cached DTOs for demolished buildings so the cache stays bounded.
		lastBuildingDtos.keySet().retainAll(liveIds);
		closedRestaurantsAnnounced.retainAll(liveIds);
	}

	/**
	 * Phase-21: replace the placeholder {@code gtfsRouteType = 0} on a fresh
	 * {@link CargoEvent.RunStarted} with the real route-type from the GTFS
	 * snapshot. The frontend uses this to pick the correct vehicle silhouette
	 * (bus / tram / ferry / cable car / …) for the cargo-carrying ambient
	 * sprite. All other event variants pass through unchanged.
	 */
	private CargoEvent resolveRouteType(CargoEvent event) {
		if (!(event instanceof CargoEvent.RunStarted started)) {
			return event;
		}
		int resolved = transitSnapshotService.routeTypeForRouteId(started.run().routeId());
		if (resolved == started.gtfsRouteType()) {
			return event;
		}
		return new CargoEvent.RunStarted(started.run(), resolved, started.gameMinutes());
	}
}
