import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {Observable, tap} from "rxjs";

import type {Milestone, MilestoneEvent, MilestonesResponse} from "../model/milestone.model";

/**
 * Phase-8 task 7 milestone tracker. Mirrors the backend {@code MilestoneTracker} via:
 * <ul>
 *   <li>An initial {@code GET /api/game/milestones} on construction to populate the
 *       unlocked-set snapshot.</li>
 *   <li>An {@link EventSource} on {@code /api/game/milestones/stream} that pushes each new
 *       unlock event as it happens; the service mutates the {@link milestones} signal and
 *       sets {@link lastEvent} so a milestone-toast component can observe via an effect.</li>
 * </ul>
 *
 * <p>Reset is handled by re-running {@link refresh} after a {@code POST /api/game/reset};
 * call sites already do that as part of their reset flow.
 */
@Injectable({providedIn: "root"})
export class MilestoneService {
	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _milestones = signal<readonly Milestone[]>([]);
	private readonly _fulfilledCount = signal<number>(0);
	private readonly _lastEvent = signal<MilestoneEvent | null>(null);

	readonly milestones = this._milestones.asReadonly();
	readonly fulfilledCount = this._fulfilledCount.asReadonly();
	readonly lastEvent = this._lastEvent.asReadonly();

	private eventSource?: EventSource;

	constructor() {
		this.refresh().subscribe({error: () => undefined});
		this.openStream();
		this.destroyRef.onDestroy(() => {
			this.eventSource?.close();
		});
	}

	refresh(): Observable<MilestonesResponse> {
		return this.httpClient.get<MilestonesResponse>("/api/game/milestones").pipe(
			tap((response) => {
				this._milestones.set(response.milestones);
				this._fulfilledCount.set(response.fulfilledCount);
			}),
		);
	}

	private openStream(): void {
		try {
			this.eventSource = new EventSource("/api/game/milestones/stream");
			this.eventSource.onmessage = (event) => {
				try {
					const parsed = JSON.parse(event.data) as MilestoneEvent;
					this._lastEvent.set(parsed);
					// Optimistically flip the unlock flag so a slow refresh doesn't dim the
					// trophy banner before the snapshot endpoint catches up.
					this._milestones.update((list) => list.map((milestone) =>
						milestone.id === parsed.milestone
							? {...milestone, unlocked: true, unlockedAtGameMinutes: parsed.gameMinutes}
							: milestone,
					));
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

