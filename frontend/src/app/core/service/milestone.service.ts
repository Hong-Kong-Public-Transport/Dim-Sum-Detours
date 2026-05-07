import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {Observable, tap} from "rxjs";

import type {Milestone, MilestoneEvent, MilestonesResponse} from "../model/milestone.model";
import {ServerEventBusService} from "./server-event-bus.service";

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
	private readonly bus = inject(ServerEventBusService);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _milestones = signal<readonly Milestone[]>([]);
	private readonly _fulfilledCount = signal<number>(0);
	private readonly _lastEvent = signal<MilestoneEvent | null>(null);

	readonly milestones = this._milestones.asReadonly();
	readonly fulfilledCount = this._fulfilledCount.asReadonly();
	readonly lastEvent = this._lastEvent.asReadonly();

	constructor() {
		this.refresh().subscribe({error: () => undefined});
		// Phase-19 Phase-G: subscribe to the unified server-event bus instead
		// of opening a dedicated /api/game/milestones/stream EventSource.
		const subscription = this.bus.milestone$.subscribe((parsed) => {
			this._lastEvent.set(parsed);
			// Optimistically flip the unlock flag so a slow refresh doesn't dim the
			// trophy banner before the snapshot endpoint catches up.
			this._milestones.update((list) => list.map((milestone) =>
				milestone.id === parsed.milestone
					? {...milestone, unlocked: true, unlockedAtGameMinutes: parsed.gameMinutes}
					: milestone,
			));
		});
		this.destroyRef.onDestroy(() => subscription.unsubscribe());
	}

	refresh(): Observable<MilestonesResponse> {
		return this.httpClient.get<MilestonesResponse>("/api/game/milestones").pipe(
			tap((response) => {
				this._milestones.set(response.milestones);
				this._fulfilledCount.set(response.fulfilledCount);
			}),
		);
	}
}

