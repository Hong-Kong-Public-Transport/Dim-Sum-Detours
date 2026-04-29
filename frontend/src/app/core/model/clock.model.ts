/**
 * Mirrors {@code com.dimsumdetours.sim.state.GameState.ClockSnapshot}.
 */
export interface ClockSnapshot {
	readonly gameMinutes: number;
	readonly dayOfWeek: number;
	readonly minuteOfDay: number;
	readonly speed: number;
	readonly playing: boolean;
}

