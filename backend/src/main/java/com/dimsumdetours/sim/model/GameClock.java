package com.dimsumdetours.sim.model;

import lombok.Getter;
import lombok.Setter;

/**
 * The simulation's authoritative time. Decoupled from real time; advances by ticks.
 *
 * <p>Frame-agnostic — the engine calls {@link #advance(long)} once per simulation tick. UI
 * concerns (auto-drop to 1× on alerts, real-time pacing) live OUTSIDE this class.
 */
@Getter
public final class GameClock {

	private long gameMinutes;
	private int speedMultiplier = 1;

	/**
	 * The simulation begins paused. The frontend's clock controls hand control to the player
	 * on first interaction (Resume / pick a speed). This avoids the "delivery already in
	 * flight before I've looked at the screen" surprise on a fresh tab.
	 */
	@Setter
	private boolean paused = true;

	/**
	 * Advance the clock by {@code deltaGameMinutes}. Caller computes the delta from real time.
	 */
	public void advance(long deltaGameMinutes) {
		if (paused) {
			return;
		}
		if (deltaGameMinutes < 0) {
			throw new IllegalArgumentException("delta must be non-negative");
		}
		this.gameMinutes += deltaGameMinutes;
	}

	public void setSpeedMultiplier(int speedMultiplier) {
		if (speedMultiplier < 0) {
			throw new IllegalArgumentException("speed must be >= 0");
		}
		this.speedMultiplier = speedMultiplier;
	}


	/**
	 * Day-of-week derived from the game clock, where 0 = Monday, 6 = Sunday.
	 */
	public int getDayOfWeek() {
		long days = gameMinutes / (60L * 24L);
		return (int) (days % 7L);
	}

	/**
	 * Minute-of-day, 0–1439. Used to look up the right GTFS service trip.
	 */
	public int getMinuteOfDay() {
		return (int) (gameMinutes % (60L * 24L));
	}

	/** Game-day index since the start of the game (0-based, monotonic). Used by
	 *  daily-upkeep / spoilage / order-deadline math that operates in days. */
	public long getGameDay() {
		return gameMinutes / (60L * 24L);
	}

	/** Game-week index since the start of the game (0-based, monotonic). One
	 *  game-week is 7 game-days. Surfaced for week-grain UI and analytics. */
	public long getGameWeek() {
		return getGameDay() / 7L;
	}

	/** Game-month index since the start of the game (0-based, monotonic). One
	 *  game-month is fixed at {@link #DAYS_PER_GAME_MONTH} game-days — chosen
	 *  for player-comprehensibility rather than calendrical accuracy.
	 *  Future phases that need month boundaries (factory upgrade timers,
	 *  monthly milestones) read this. */
	public long getGameMonth() {
		return getGameDay() / DAYS_PER_GAME_MONTH;
	}

	/** Game-year index since the start of the game (0-based, monotonic). One
	 *  game-year is {@link #MONTHS_PER_GAME_YEAR} game-months = 360 game-days
	 *  (12 × 30). Tidy multiples that gameplay code can reason about without
	 *  per-month bookkeeping. */
	public long getGameYear() {
		return getGameMonth() / MONTHS_PER_GAME_YEAR;
	}

	/** 30 game-days per game-month. Tidy enough for in-game calendars without
	 *  the irregular real-world month-length lookup table. */
	public static final long DAYS_PER_GAME_MONTH = 30L;
	/** 12 game-months per game-year. */
	public static final long MONTHS_PER_GAME_YEAR = 12L;

	/** Reset clock to t=0, 1× speed, paused (matches a fresh-tab boot). */
	public void reset() {
		this.gameMinutes = 0L;
		this.speedMultiplier = 1;
		this.paused = true;
	}
}
