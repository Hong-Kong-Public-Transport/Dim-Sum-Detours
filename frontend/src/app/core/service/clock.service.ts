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
 */
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

	private eventSource?: EventSource;

	constructor() {
		// One initial fetch so the UI has a number even before the first SSE frame.
		this.refresh().subscribe({error: () => undefined});
		this.openStream();
		this.destroyRef.onDestroy(() => this.eventSource?.close());
	}

	refresh(): Observable<ClockSnapshot> {
		return this.httpClient.get<ClockSnapshot>("/api/game/clock").pipe(
			tap((snapshot) => this._snapshot.set(snapshot)),
		);
	}

	setSpeed(speed: number): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/speed", {speed}).pipe(
			tap((snapshot) => this._snapshot.set(snapshot)),
		);
	}

	pause(): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/pause", {}).pipe(
			tap((snapshot) => this._snapshot.set(snapshot)),
		);
	}

	resume(): Observable<ClockSnapshot> {
		return this.httpClient.post<ClockSnapshot>("/api/game/clock/resume", {}).pipe(
			tap((snapshot) => this._snapshot.set(snapshot)),
		);
	}

	private openStream(): void {
		try {
			this.eventSource = new EventSource("/api/game/clock/stream");
			this.eventSource.onmessage = (event) => {
				try {
					const snapshot = JSON.parse(event.data) as ClockSnapshot;
					this._snapshot.set(snapshot);
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

