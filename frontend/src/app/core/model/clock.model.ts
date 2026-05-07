/**
 * Mirrors {@code com.dimsumdetours.sim.state.GameState.ClockSnapshot}.
 *
 * Phase-19: this is a self-contained anchor envelope — see {@code
 * docs/NETWORKING.md}. Every server-emitted event also carries
 * {@code serverWallClockMs} + {@code worldEpoch} so the client extrapolates
 * `liveGameMinutes()` from the most recent anchor and reconciles by
 * absolute time + epoch (never by relative event ordering).
 */
export interface ClockSnapshot {
	readonly gameMinutes: number;
	readonly dayOfWeek: number;
	readonly minuteOfDay: number;
	/** Game-day index since game start (0-based, monotonic). */
	readonly gameDay: number;
	/** Game-week index since game start (0-based, monotonic). */
	readonly gameWeek: number;
	/** Game-month index since game start (0-based, monotonic).
	 *  One game-month = 30 game-days (fixed). */
	readonly gameMonth: number;
	/** Game-year index since game start (0-based, monotonic).
	 *  One game-year = 12 game-months = 360 game-days (fixed). */
	readonly gameYear: number;
	readonly speed: number;
	readonly playing: boolean;
	/** {@code System.currentTimeMillis()} on the server when emitted. The
	 *  client extrapolates with {@code (Date.now() - serverWallClockMs)
	 *  * speed} so fixed network latency cancels in the math. */
	readonly serverWallClockMs: number;
	/** In-game minute the clock was paused at; {@code null} unless paused.
	 *  When paused, the client clamps {@code liveGameMinutes()} to this
	 *  value rather than extrapolating forward. */
	readonly pausedSinceGameMinutes: number | null;
	/** Bumped on every server reset. Any received envelope whose epoch
	 *  doesn't match the cached value triggers a cold-boot
	 *  re-fetch via {@code GET /api/game/snapshot}. */
	readonly worldEpoch: number;
}
