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
	public static final long STARTING_BALANCE = 10_000L;

	/**
	 * One-off cost to place each kind of building. Restaurants are NPC-spawned (the player
	 * doesn't open their own — they ship to existing ones), so their build cost is 0; the
	 * field stays for symmetry should manual placement ever return.
	 */
	public static final long FARM_BUILD_COST = 500L;
	public static final long FACTORY_BUILD_COST = 1_500L;
	public static final long RESTAURANT_BUILD_COST = 0L;

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

	/**
	 * Minimum geodesic spacing between any two same-kind buildings, in metres. Acts as a soft
	 * "one building per π·r² area" cap so a player can't spam a thousand farms onto the same
	 * park. The frontend mirrors this in {@code placement-validator.ts} for cursor preview.
	 */
	public static final double MIN_BUILDING_SPACING_METERS = 100.0;

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

	/**
	 * Fallback payout for fixture restaurants placed without a {@link com.dimsumdetours.sim.model.RestaurantTemplate}
	 * (legacy / test helpers). Real restaurants always carry a template id and use its
	 * {@code basePayout}.
	 */
	public static final long DEFAULT_RESTAURANT_PAYOUT = 600L;

	/**
	 * Number of restaurants the frontend auto-spawner drops onto the map once placement zones
	 * resolve. Mirrored on the frontend in {@code GAME_CONSTANTS.spawn.restaurantsPerWorld};
	 * kept in sync even though the spawn loop currently lives client-side.
	 */
	public static final int RESTAURANTS_PER_WORLD = 6;

	/**
	 * Phase-7 procedural order generator. Every {@code ORDER_GENERATION_INTERVAL_GAME_MINUTES}
	 * a random open restaurant has one of its accepted recipes added as a pending {@link com.dimsumdetours.sim.model.Order}
	 * (capped at {@code MAX_PENDING_ORDERS_PER_RESTAURANT} so a neglected restaurant stops
	 * generating new noise instead of stacking expirations forever).
	 */
	public static final long ORDER_GENERATION_INTERVAL_GAME_MINUTES = 30L;
	public static final int MAX_PENDING_ORDERS_PER_RESTAURANT = 3;

	// ─────────────────────────────────────────────────────────────────────────────
	// Vehicles (starting tier)
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Capacity (in ingredient units) of the starting vehicle — a horse cart.
	 */
	public static final int STARTING_VEHICLE_CAPACITY = 5;

	// ─────────────────────────────────────────────────────────────────────────────
	// Phase 8: walker model + refrigeration + milestones
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Base walking speed of an autonomous ingredient-walker, in metres per game-minute.
	 * Bus legs along a routed path use the GTFS-driven travel time directly so they're much
	 * faster — that's the README's "ingredients walk slowly but can take a bus to speed up"
	 * promise. Mirrored in the frontend's {@code GAME_CONSTANTS.walker.metersPerGameMinute}.
	 */
	public static final double WALKER_METERS_PER_GAME_MINUTE = 80.0;

	/**
	 * Speed multiplier applied to the bus leg of a walker's path, relative to the base walk
	 * speed. The frontend uses this to allocate proportional time to the middle leg of a
	 * 4-waypoint GTFS-stop route (source → stop → stop → dest), so the marker noticeably
	 * accelerates between the two stops.
	 */
	public static final double WALKER_BUS_SPEED_MULTIPLIER = 6.0;

	/**
	 * Phase-12 robot model: casual-biking speed in metres per game-minute. 10 km/h
	 * physical → ~167 m / 60 s; we round to 170 to match the existing convention where
	 * 1 game-minute is treated as one wall-clock minute for speed math (the legacy
	 * walker constant 80 m/game-min ≈ 4.8 km/h followed the same convention). Slower
	 * than a bus, faster than the deprecated walker — robots feel like couriers, not
	 * pedestrians. Mirrored on the frontend in {@code GAME_CONSTANTS.robot.metersPerGameMinute}.
	 */
	public static final double ROBOT_METERS_PER_GAME_MINUTE = 170.0;

	/**
	 * Phase-12 dispatcher heuristic: how many full per-cycle input sets a factory will
	 * stockpile before the dispatcher stops topping it up. A value of 2 keeps roughly
	 * one cycle's worth on hand plus a buffer so the line doesn't stall the moment a
	 * walker leaves with the last unit.
	 */
	public static final int FACTORY_RESTOCK_TARGET_CYCLES = 2;

	/**
	 * One-off cost to upgrade a factory to refrigerated. Pauses the cargo's spoilage clock
	 * while finished units sit in the factory between cycle completion and dispatch — the
	 * "Cold Chain" milestone unlocks the upgrade. The fee is non-refundable on demolish.
	 */
	public static final long REFRIGERATION_UPGRADE_COST = 2_000L;

	/**
	 * City-Builder soft-win threshold: cumulative number of fulfilled orders across the whole
	 * map. Hit it and the milestone modal pops a celebratory teaser — a real win condition
	 * (district unlocks, population threshold per the README) is a Phase-9 concern.
	 */
	public static final long CITY_BUILDER_FULFILLED_ORDER_TARGET = 50L;

	/**
	 * Sliding window (in game-minutes) used by the Transit Tycoon milestone tracker. Counts
	 * how many distinct GTFS routes carried at least one shipment within the window; ten
	 * concurrent routes trips the milestone.
	 */
	public static final long TRANSIT_TYCOON_WINDOW_GAME_MINUTES = 1_440L;
	public static final int TRANSIT_TYCOON_DISTINCT_ROUTE_TARGET = 10;

	// ─────────────────────────────────────────────────────────────────────────────
	// Phase 14: OSM street pathfinding
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Cache key version for the Overpass {@code way["highway"]} response. Bump when the
	 * underlying QL template changes so old cache files are skipped.
	 */
	public static final String OSM_HIGHWAYS_CACHE_VERSION = "v1";

	/**
	 * Maximum great-circle distance (metres) the OSM router will accept when snapping
	 * a building's lat/lon to its nearest street-graph node. Beyond this the router
	 * falls back to the straight-line two-point path — happens when a building sits
	 * inside a placement zone with no nearby way (e.g. a Country Park interior in
	 * Hong Kong, or the middle of a US-style oversize parking lot).
	 *
	 * <p>Tuned at 1 km because Hong Kong's placement zones include Country Parks
	 * whose centroids can sit several hundred metres from the nearest cycleway.
	 * The earlier 250 m value was right for Bellingham (every park has a perimeter
	 * road nearby) but caused most HK robots to fall back to straight-line.
	 */
	public static final double OSM_MAX_SNAP_METERS = 1_000.0;

	/**
	 * Hard cap on the number of A* node expansions before the router gives up and
	 * falls back to straight-line. Guards against pathological edge cases (disconnected
	 * components, malformed OSM data) bleeding into the simulation tick.
	 */
	public static final int OSM_MAX_ASTAR_EXPANSIONS = 200_000;

	/**
	 * Phase-15: maximum great-circle distance (metres) a single robot leg may cover.
	 * Beyond this the dispatcher refuses to spawn the robot — the cargo must instead
	 * route via a multi-leg plan that puts a bus / train in the middle. Both a realism
	 * lever (a delivery cyclist isn't going to ride 30 km across Hong Kong from
	 * Sheung Wan to Sai Kung) and a perf lever (caps the OSM A* search radius). Bus
	 * and train legs are exempt — they're scheduled vehicles riding fixed corridors
	 * for which any distance is reasonable.
	 */
	public static final double MAX_ROBOT_LEG_METERS = 5_000.0;

	// Phase-21: the previous {@code MAX_TRANSIT_STOP_WALK_METERS = 1500} cap on
	// the source→boardingStop / alightingStop→destination "last-mile" robot legs
	// has been replaced by the unified {@link #MAX_ROBOT_LEG_METERS} cap — every
	// robot leg in the simulation now obeys the same 5 km rule, including the two
	// last-mile legs of a robot→bus→robot chain. If neither last-mile leg can be
	// kept under that cap, the planner returns no chain and the dispatcher falls
	// back to a direct robot (when source-to-destination is itself ≤ 5 km), or
	// reports "no path" otherwise.

	/**
	 * Phase-16: scheduled-speed approximation for GTFS bus legs when the planner is
	 * computing arrival times without consulting per-trip stop_times. Tuned to a
	 * loose 25 km/h average accounting for stops and signals — much faster than the
	 * 10 km/h robot pace, slow enough that long-haul plans still feel costly.
	 */
	public static final double BUS_METERS_PER_GAME_MINUTE = 420.0;

	// ─────────────────────────────────────────────────────────────────────────────
	// Phase 17: robot batching + loading window
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Phase-17: robots ship in fixed batches of this many units. Aliased to the
	 * starting cart capacity (5) so a single shipment fills a whole truck rather
	 * than the dispatcher firing one robot per single unit. Restaurants whose
	 * order quantity is below the batch are still served by a 5-unit shipment;
	 * the surplus is discarded on arrival (the order is fulfilled regardless).
	 */
	public static final int ROBOT_CARGO_BATCH_SIZE = STARTING_VEHICLE_CAPACITY;

	/**
	 * Phase-17: how long a freshly-spawned vehicle "loads" at its source before
	 * it starts moving. The window matters less for gameplay (5 game-minutes at
	 * 1× = 5 wall-clock seconds; almost nothing at 256×) than for SSE
	 * resilience: by encoding a stationary head on every Spawned event, the
	 * frontend has a guaranteed window to render the robot at its origin
	 * regardless of when the next animation frame fires — robots stop appearing
	 * to teleport when arrivals batch within a single real-time second.
	 *
	 * <p>Mirrored on the frontend in {@code GAME_CONSTANTS.robot.loadingGameMinutes}
	 * so the drawer's progress bar can show the loading vs. travel split.
	 */
	public static final long ROBOT_LOADING_GAME_MINUTES = 5L;

	// ─────────────────────────────────────────────────────────────────────────────
	// Phase 18: ambient transit
	// ─────────────────────────────────────────────────────────────────────────────
	/**
	 * Phase-18: every transit route runs a vehicle at this constant in-game cadence,
	 * regardless of the published GTFS schedule. We keep the GTFS feed for spatial
	 * data (stop locations, route shapes, ordered stop sequences) but ignore its
	 * timetable — real-world schedules thin out overnight and surge at peak which
	 * makes for inscrutable "no buses available" gaps in a game. A flat 5-minute
	 * headway keeps transit always-eventually-available regardless of in-game
	 * time-of-day.
	 */
	public static final long BUS_HEADWAY_GAME_MINUTES = 5L;

	/**
	 * Phase-18: zoom-level at and above which the frontend renders ambient transit
	 * (stops + buses sliding along their routes). At lower zooms the markers turn
	 * into visual noise and a busy feed (1k+ stops) hurts FPS. Mirrored on the
	 * frontend in {@code GAME_CONSTANTS.map.minTransitRenderZoom}.
	 */
	public static final int MIN_TRANSIT_RENDER_ZOOM = 12;
}
