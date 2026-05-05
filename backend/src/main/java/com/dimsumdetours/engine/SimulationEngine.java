package com.dimsumdetours.engine;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.model.Milestone;
import com.dimsumdetours.sim.model.MilestoneEvent;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.MilestoneTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

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

	private final MilestoneTracker milestoneTracker = new MilestoneTracker();

	private @org.jspecify.annotations.Nullable Disposable tickSubscription;

	/** Carry-over fractional minutes between ticks so the clock stays integer. */
	private double minuteAccumulator = 0.0;

	/** Phase-14: last game-minute on which the milestone evaluator ran. The evaluator
	 * is idempotent within a game-minute — none of its predicates can flip without the
	 * clock advancing — so skipping it on duplicate-minute ticks (paused, or fractional
	 * accumulator hasn't crossed a boundary yet) drops a chunk of per-tick work. */
	private long lastEvaluatedGameMinute = Long.MIN_VALUE;

	@PostConstruct
	void start() {
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
		}
	}

	private void tick() {
		try {
			GameState.ClockSnapshot before = gameState.getClockSnapshot();
			if (before.playing() && before.speed() > 0) {
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
			GameState.ArrivalBatch arrivals = gameState.advanceVehicles();
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
				|| lastEmittedSnapshot.speed() != after.speed();
			if (stateChanged || nowWallMs - lastClockEmitWallMs >= CLOCK_SSE_INTERVAL_MS) {
				lastClockEmitWallMs = nowWallMs;
				lastEmittedSnapshot = after;
				clockSink.tryEmitNext(after);
			}
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

	/** Tracker handle so the API can serve the snapshot endpoint and feed fulfillments in. */
	public MilestoneTracker milestoneTracker() {
		return milestoneTracker;
	}
}
