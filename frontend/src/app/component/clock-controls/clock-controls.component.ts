import {ChangeDetectionStrategy, Component, computed, inject} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ButtonModule} from "primeng/button";
import {TooltipModule} from "primeng/tooltip";

import {GAME_CONSTANTS} from "../../core/constant/game.constants";
import {ClockService} from "../../core/service/clock.service";

const DAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;

/**
 * Toolbar widget showing the in-game day-of-week + HH:MM and a row of speed buttons
 * (⏸ 1× 4× 16× 64× 256×). Active speed is highlighted; pressing pause flips
 * {@code playing} via the backend.
 */
@Component({
	selector: "app-clock-controls",
	imports: [TranslocoDirective, ButtonModule, TooltipModule],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./clock-controls.component.html",
	styleUrl: "./clock-controls.component.scss",
})
export class ClockControlsComponent {
	private readonly clockService = inject(ClockService);

	protected readonly snapshot = this.clockService.snapshot;
	/** Phase-17: lerped game-minute (refreshes ~6 Hz) so the HH:MM toolbar display
	 * advances smoothly between authoritative SSE snapshots instead of stepping by
	 * whole-second jumps even though the wire only ticks once per real second. */
	private readonly liveGameMinutes = this.clockService.liveGameMinutesSignal;

	/** Non-zero speeds shown as buttons. */
	protected readonly speeds: readonly number[] = GAME_CONSTANTS.clock.speeds.filter((speed) => speed > 0);

	protected readonly timeLabel = computed(() => {
		const totalMinutes = Math.floor(this.liveGameMinutes());
		const minuteOfDay = ((totalMinutes % 1440) + 1440) % 1440;
		const hours = Math.floor(minuteOfDay / 60);
		const minutes = minuteOfDay % 60;
		return `${hours.toString().padStart(2, "0")}:${minutes.toString().padStart(2, "0")}`;
	});

	/** "mon" / "tue" / … — joined to the i18n key {@code app.clock.day.<key>}.
	 * Computed against the live game-minute so it flips at game-midnight even
	 * between SSE frames at high speed. */
	protected readonly dayKey = computed(() => {
		const totalMinutes = Math.floor(this.liveGameMinutes());
		const liveDay = Math.floor(((totalMinutes / 1440) % 7 + 7) % 7);
		return DAY_KEYS[liveDay] ?? "mon";
	});

	protected setSpeed(speed: number): void {
		this.clockService.setSpeed(speed).subscribe({error: () => undefined});
	}

	protected togglePause(): void {
		const playing = this.snapshot().playing;
		(playing ? this.clockService.pause() : this.clockService.resume())
			.subscribe({error: () => undefined});
	}
}
