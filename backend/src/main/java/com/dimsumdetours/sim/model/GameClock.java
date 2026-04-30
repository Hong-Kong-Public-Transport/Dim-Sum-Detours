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

	/** Reset clock to t=0, 1× speed, paused (matches a fresh-tab boot). */
	public void reset() {
		this.gameMinutes = 0L;
		this.speedMultiplier = 1;
		this.paused = true;
	}
}
