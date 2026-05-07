import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {Observable, tap} from "rxjs";

import type {ClockSnapshot} from "../model/clock.model";
import {ServerEventBusService} from "./server-event-bus.service";

/**
 * Phase 4: client-side mirror of the backend simulation clock. Connects an {@link EventSource}
 * to {@code /api/game/clock/stream} on first construction; updates the {@link snapshot} signal
 * on each tick. Mutations go through {@link #setSpeed} / {@link #pause} / {@link #resume},
 * which POST to the backend; the SSE stream then echoes the new state back, keeping the
 * signal authoritative.
 *
 * <p>Phase-13: also exposes {@link #liveGameMinutes} which extrapolates the latest snapshot
 * forward using wall-clock time. The PixiJS robot layer reads this on every animation
 * frame so motion stays smooth even between server SSE frames (the backend ticks every
 * 100ms, but a 60fps RAF loop wants ~16ms granularity).
 *
 * <p>Phase-17: also exposes {@link #liveGameMinutesSignal} — a low-frequency signal
 * (~6 Hz) that lerp-driven UI ({@link import("../../component/clock-controls/clock-controls.component").ClockControlsComponent}'s
 * HH:MM display, the farm/factory drawers' {@code producedUnits} counter) reads through
 * a {@code computed()} so the displayed values advance smoothly between snapshot SSE
 * frames instead of stepping by whole-second jumps.
 */
const GAME_MINUTES_PER_REAL_SECOND_AT_1X = 1.0;
/** Wall-clock period at which {@link liveGameMinutesSignal} is refreshed. ~6 Hz is
 * smooth enough for human-readable HH:MM and integer production counters; 60 Hz is
 * reserved for the Pixi RAF loop, which doesn't go through Angular signals. */
const LIVE_TICK_INTERVAL_MS = 150;

@Injectable({providedIn: "root"})
export class ClockService {
	private readonly httpClient = inject(HttpClient);
	private readonly bus = inject(ServerEventBusService);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _snapshot = signal<ClockSnapshot>({
		gameMinutes: 0,
		dayOfWeek: 0,
		minuteOfDay: 0,
		gameDay: 0,
		gameWeek: 0,
		gameMonth: 0,
		gameYear: 0,
		speed: 1,
		playing: true,
		serverWallClockMs: Date.now(),
		pausedSinceGameMinutes: null,
		worldEpoch: 0,
	});

	readonly snapshot = this._snapshot.asReadonly();

	/** Phase-17: a high-frequency mirror of {@link liveGameMinutes} surfaced as a signal
	 * so OnPush components can lerp displayed values (HH:MM in the toolbar, integer
	 * {@code producedUnits} counters in the farm/factory drawers) without waiting for
	 * the next per-second SSE snapshot frame. Updated on a {@link LIVE_TICK_INTERVAL_MS}
	 * wall-clock interval. */
	private readonly _liveGameMinutes = signal<number>(0);
	readonly liveGameMinutesSignal = this._liveGameMinutes.asReadonly();

	private liveTickHandle?: ReturnType<typeof setInterval>;

	/** Wall-clock millisecond offset between local clock and server clock. Used by
	 * {@link liveGameMinutes} to extrapolate the game-minute forward between SSE frames.
	 * Uses {@link Date#now} rather than {@code performance.now()} so the eslint browser
	 * globals config doesn't need a dedicated entry — the millisecond resolution is more
	 * than enough for animation lerping.
	 *
	 * Phase-19: this is now {@code Date.now() - serverWallClockMs} — the
	 * fixed-latency offset. Multiplying live elapsed time by `speed` against
	 * THIS offset means a re-anchor frame from the server with a different
	 * `serverWallClockMs` automatically corrects any drift between the
	 * client and server wall-clocks.
	 */
	private localOffsetMs = 0;

	constructor() {
		// One initial fetch so the UI has a number even before the first SSE frame.
		this.refresh().subscribe({error: () => undefined});
		// Phase-19 Phase-G: subscribe to the unified server-event bus instead
		// of opening a dedicated /api/game/clock/stream EventSource.
		const subscription = this.bus.clock$.subscribe((snapshot) => this.applySnapshot(snapshot));
		this.liveTickHandle = setInterval(() => {
			this._liveGameMinutes.set(this.liveGameMinutes());
		}, LIVE_TICK_INTERVAL_MS);
		this.destroyRef.onDestroy(() => {
			subscription.unsubscribe();
			if (this.liveTickHandle !== undefined) {
				clearInterval(this.liveTickHandle);
			}
		});
	}

	refresh(): Observable<ClockSnapshot> {
		return this.httpClient.get<ClockSnapshot>("/api/game/clock").pipe(
			tap((snapshot) => this.applySnapshot(snapshot)),
		);
	}

	setSpeed(speed: number): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/speed", {speed}).pipe(
			tap((snapshot) => this.applySnapshot(snapshot)),
		);
	}

	pause(): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/pause", {}).pipe(
			tap((snapshot) => this.applySnapshot(snapshot)),
		);
	}

	resume(): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/resume", {}).pipe(
			tap((snapshot) => this.applySnapshot(snapshot)),
		);
	}

	/**
	 * Game-minute extrapolated to the current wall-clock instant. Used by the robot
	 * Pixi layer's animation loop to lerp smoothly between server SSE frames. Reads as
	 * a plain number — NOT a signal — so it can be called 60 times a second without
	 * triggering Angular change detection.
	 *
	 * Phase-19: extrapolation uses {@code (Date.now() - serverWallClockMs - offset)}
	 * so fixed network latency cancels in the math. When paused, returns
	 * {@code pausedSinceGameMinutes ?? gameMinutes} — the server includes
	 * the pause anchor in every envelope so a subscriber that joined while
	 * paused renders the right value without waiting.
	 */
	liveGameMinutes(): number {
		const snapshot = this._snapshot();
		if (!snapshot.playing || snapshot.speed <= 0) {
			return snapshot.pausedSinceGameMinutes ?? snapshot.gameMinutes;
		}
		const elapsedSeconds =
			(Date.now() - snapshot.serverWallClockMs - this.localOffsetMs) / 1000;
		return snapshot.gameMinutes
			+ elapsedSeconds * snapshot.speed * GAME_MINUTES_PER_REAL_SECOND_AT_1X;
	}

	/** Phase-19: current world epoch. Bumped on every server reset. Other
	 * services compare each received envelope's epoch against this value
	 * and trigger a cold-boot on mismatch. */
	worldEpoch(): number {
		return this._snapshot().worldEpoch;
	}

	private applySnapshot(snapshot: ClockSnapshot): void {
		const previousEpoch = this._snapshot().worldEpoch;
		this._snapshot.set(snapshot);
		// `localOffsetMs` is the difference between our local wall-clock and
		// the server's wall-clock, captured at the moment this snapshot was
		// emitted. Re-deriving it on every snapshot keeps the offset
		// fresh enough to absorb modest clock drift / NTP slew between
		// frames (the throttled clock SSE arrives ~once per real second).
		this.localOffsetMs = Date.now() - snapshot.serverWallClockMs;
		// Snap the lerp signal to the freshly-arrived authoritative value so the
		// displayed HH:MM doesn't briefly flicker backwards if our extrapolation
		// had drifted ahead of the server tick.
		this._liveGameMinutes.set(snapshot.gameMinutes);
		// Phase-19: epoch transition surface. Other services subscribe to the
		// snapshot signal via effects and trigger a cold-boot when their
		// cached epoch doesn't match.
		if (previousEpoch !== snapshot.worldEpoch) {
			// Bump a one-shot epoch signal to wake any subscribers.
			this._epochCounter.update((n) => n + 1);
		}
	}

	private readonly _epochCounter = signal<number>(0);
	/** Increments every time the {@link worldEpoch} changes. Other services
	 * read this in an {@code effect()} to know when to drop their caches +
	 * re-fetch from {@code /api/game/snapshot}. */
	readonly epochCounter = this._epochCounter.asReadonly();
}
