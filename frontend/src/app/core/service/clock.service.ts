import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {Observable, tap} from "rxjs";

import type {ClockSnapshot} from "../model/clock.model";

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
 */
const GAME_MINUTES_PER_REAL_SECOND_AT_1X = 1.0;

@Injectable({providedIn: "root"})
export class ClockService {
	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _snapshot = signal<ClockSnapshot>({
		gameMinutes: 0,
		dayOfWeek: 0,
		minuteOfDay: 0,
		speed: 1,
		playing: true,
	});

	readonly snapshot = this._snapshot.asReadonly();

	/** Wall-clock millisecond at which {@link _snapshot} was last assigned. Used by
	 * {@link liveGameMinutes} to extrapolate the game-minute forward between SSE frames.
	 * Uses {@link Date#now} rather than {@code performance.now()} so the eslint browser
	 * globals config doesn't need a dedicated entry — the millisecond resolution is more
	 * than enough for animation lerping. */
	private lastSnapshotWallMs = Date.now();

	private eventSource?: EventSource;

	constructor() {
		// One initial fetch so the UI has a number even before the first SSE frame.
		this.refresh().subscribe({error: () => undefined});
		this.openStream();
		this.destroyRef.onDestroy(() => this.eventSource?.close());
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
	 */
	liveGameMinutes(): number {
		const snapshot = this._snapshot();
		if (!snapshot.playing || snapshot.speed <= 0) {
			return snapshot.gameMinutes;
		}
		const elapsedSeconds = (Date.now() - this.lastSnapshotWallMs) / 1000;
		return snapshot.gameMinutes
			+ elapsedSeconds * snapshot.speed * GAME_MINUTES_PER_REAL_SECOND_AT_1X;
	}

	private applySnapshot(snapshot: ClockSnapshot): void {
		this._snapshot.set(snapshot);
		this.lastSnapshotWallMs = Date.now();
	}

	private openStream(): void {
		try {
			this.eventSource = new EventSource("/api/game/clock/stream");
			this.eventSource.onmessage = (event) => {
				try {
					const snapshot = JSON.parse(event.data) as ClockSnapshot;
					this.applySnapshot(snapshot);
				} catch {
					// Malformed frame — ignore.
				}
			};
			this.eventSource.onerror = () => {
				// Browser auto-reconnects; nothing to do.
			};
		} catch {
			// SSE unavailable (e.g. SSR pre-render); fall back to manual refresh.
		}
	}
}
