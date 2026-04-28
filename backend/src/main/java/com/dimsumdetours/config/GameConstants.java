package com.dimsumdetours.config;

/**
 * All tunable game constants live here. ONE place to look, ONE place to balance.
 *
 * <p>Rules:
 * <ul>
 *   <li>Numbers only (or pure data). No Spring, no I/O.</li>
 *   <li>Group by subsystem with section comments.</li>
 *   <li>Anything player-facing or moddable should live in JSON content files instead — these are
 *       hard engine constants (limits, defaults, scaling factors).</li>
 * </ul>
 */
public final class GameConstants {

	private GameConstants() {
		// utility class
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// Economy
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Money the player begins a new game with.
	 */
	public static final long STARTING_BALANCE = 50_000L;

	/**
	 * Daily upkeep deducted from each owned building, regardless of output.
	 */
	public static final long FARM_DAILY_UPKEEP = 100L;
	public static final long FACTORY_DAILY_UPKEEP = 250L;
	public static final long RESTAURANT_DAILY_UPKEEP = 150L;

	/**
	 * Base transit fare per shipment per boarded vehicle (route-tier-modified).
	 */
	public static final long BASE_TRANSIT_FARE = 25L;

	// ─────────────────────────────────────────────────────────────────────────────
	// Time
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * At 1× speed, one real second equals this many in-game minutes.
	 */
	public static final double GAME_MINUTES_PER_REAL_SECOND_AT_1X = 1.0;

	/**
	 * Available game speed multipliers. The clock auto-drops to 1× on critical alerts.
	 */
	public static final int[] GAME_SPEEDS = {0, 1, 4, 16, 64, 256};

	/**
	 * How often the simulation engine ticks, in milliseconds of real time.
	 */
	public static final long SIM_TICK_MILLIS = 100L;

	// ─────────────────────────────────────────────────────────────────────────────
	// Map / placement
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Side length of a farm tile, in meters.
	 */
	public static final int FARM_TILE_METERS = 50;
	public static final int FACTORY_TILE_METERS = 40;
	public static final int RESTAURANT_TILE_METERS = 25;

	// ─────────────────────────────────────────────────────────────────────────────
	// Factories (op-graph)
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Op-slots available per factory tier, indexed by tier-1 (T1 = index 0).
	 */
	public static final int[] FACTORY_OP_SLOTS_BY_TIER = {3, 6, 12, 24};

	// ─────────────────────────────────────────────────────────────────────────────
	// Restaurants (pressure)
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * A restaurant closes when reputation falls below this (0.0 – 1.0).
	 */
	public static final double RESTAURANT_CLOSE_REPUTATION_THRESHOLD = 0.20;

	/**
	 * Reputation gain on on-time delivery; loss on late/missed delivery.
	 */
	public static final double REPUTATION_GAIN_ON_TIME = 0.05;
	public static final double REPUTATION_LOSS_LATE = 0.03;
	public static final double REPUTATION_LOSS_MISSED = 0.10;

	/**
	 * Discount applied to a late delivery's payout (0.0 – 1.0).
	 */
	public static final double LATE_DELIVERY_PAYOUT_MULTIPLIER = 0.5;

	// ─────────────────────────────────────────────────────────────────────────────
	// Vehicles (starting tier)
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Capacity (in ingredient units) of the starting vehicle — a horse cart.
	 */
	public static final int STARTING_VEHICLE_CAPACITY = 5;
}
