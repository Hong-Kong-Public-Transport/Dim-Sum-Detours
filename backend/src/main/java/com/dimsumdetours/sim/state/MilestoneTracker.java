package com.dimsumdetours.sim.state;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Milestone;
import com.dimsumdetours.sim.model.Restaurant;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase-8 task 7: in-memory milestone tracker. Lives in the framework-agnostic {@code sim/}
 * package — Spring wraps it via {@link com.dimsumdetours.engine.SimulationEngine}.
 *
 * <p>The tracker is fed two signals: order fulfillments (via {@link #recordFulfillment}) and
 * the live building list (via {@link #evaluate}). On each engine tick the engine asks the
 * tracker to evaluate its predicates against the current {@link GameState}; any newly-flipped
 * milestones are returned and broadcast on the SSE stream.
 *
 * <p>Per-milestone progress notes:
 * <ul>
 *   <li>{@link Milestone#FIRST_DELIVERY} — flips on the first {@link #recordFulfillment}.</li>
 *   <li>{@link Milestone#COLD_CHAIN} — flips on the first non-spoiled fulfillment of cargo
 *       whose source recipe declares any output with {@code shelfLifeMinutes > 0}. We don't
 *       have ingredient-level metadata in the tracker, so the engine passes a
 *       {@code perishable} flag from the order's recipe at fulfillment-time.</li>
 *   <li>{@link Milestone#NEIGHBORHOOD_HERO} — placeholder for now: trips when at least three
 *       open restaurants sit at reputation ≥ 0.80. The README's "for seven game-days" gate
 *       is a Phase-9 refinement (needs a per-restaurant rolling-average track).</li>
 *   <li>{@link Milestone#VERTICAL_INTEGRATION} — flips when at least one {@code recipeId} is
 *       represented by a farm + factory + restaurant simultaneously.</li>
 *   <li>{@link Milestone#CUISINE_MASTER} — flips when fulfilled-recipe count for any single
 *       cuisine reaches the cuisine's recipe count. Cuisine metadata is not yet on
 *       {@code Recipe} (Phase 9 work); the engine passes a cuisine string at
 *       fulfillment-time so the tracker doesn't need a registry handle.</li>
 *   <li>{@link Milestone#TRANSIT_TYCOON} — flips when distinct-route count inside the
 *       {@link GameConstants#TRANSIT_TYCOON_WINDOW_GAME_MINUTES} window reaches the target.</li>
 *   <li>{@link Milestone#CITY_BUILDER} — flips when cumulative-fulfilled-orders crosses the
 *       {@link GameConstants#CITY_BUILDER_FULFILLED_ORDER_TARGET}. The "soft-win" teaser.</li>
 * </ul>
 */
public final class MilestoneTracker {

	private final ReentrantLock lock = new ReentrantLock();
	private final EnumSet<Milestone> unlocked = EnumSet.noneOf(Milestone.class);
	private final EnumMap<Milestone, Long> unlockedAtGameMinutes = new EnumMap<>(Milestone.class);

	/** Cumulative on-time fulfillments (LATE counts as half — same payout treatment). */
	private long fulfilledCount;
	/** Per-cuisine fulfilled count; trips Cuisine Master once it reaches the cuisine's size. */
	private final Object2LongOpenHashMap<String> fulfilledByCuisine = new Object2LongOpenHashMap<>();
	/** Recent route-id usages, with the game-minute they last carried a shipment. */
	private final Object2LongOpenHashMap<String> recentRouteUsages = new Object2LongOpenHashMap<>();

	/**
	 * Record an order fulfillment. {@code perishable} flips the COLD_CHAIN flag (the order
	 * arrived without spoilage on a perishable cargo); {@code cuisine} accumulates against
	 * the per-cuisine bucket; {@code routeId} feeds the Transit Tycoon window.
	 */
	public void recordFulfillment(
		long gameMinutes,
		boolean perishable,
		String cuisine,
		String routeId
	) {
		lock.lock();
		try {
			fulfilledCount++;
			if (cuisine != null && !cuisine.isBlank()) {
				fulfilledByCuisine.addTo(cuisine, 1L);
			}
			if (routeId != null && !routeId.isBlank()) {
				recentRouteUsages.put(routeId, gameMinutes);
			}
			if (!unlocked.contains(Milestone.FIRST_DELIVERY)) {
				unlock(Milestone.FIRST_DELIVERY, gameMinutes);
			}
			if (perishable && !unlocked.contains(Milestone.COLD_CHAIN)) {
				unlock(Milestone.COLD_CHAIN, gameMinutes);
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Per-tick evaluator. Inspects the live {@link GameState} for milestone predicates that
	 * depend on instantaneous world state (vertical integration, neighborhood hero, transit
	 * tycoon route window, city builder counter) and flips any that newly hold.
	 *
	 * <p>{@code cuisineSizes} is an externally-maintained map of cuisine id → total recipe
	 * count, used to know when a cuisine has been fully cleared.
	 *
	 * @return list of milestones unlocked on THIS tick (empty when nothing changed).
	 */
	public List<Milestone> evaluate(GameState state, Map<String, Long> cuisineSizes, long now) {
		lock.lock();
		try {
			List<Milestone> newlyUnlocked = new ArrayList<>();
			List<Building> buildings = state.listBuildings();

			// VERTICAL_INTEGRATION: any recipe id covered by farm + factory + restaurant.
			if (!unlocked.contains(Milestone.VERTICAL_INTEGRATION)) {
				Set<String> farmRecipes = new HashSet<>();
				Set<String> factoryRecipes = new HashSet<>();
				Set<String> restaurantRecipes = new HashSet<>();
				for (Building building : buildings) {
					switch (building.kind()) {
						case FARM -> farmRecipes.add(building.recipeId());
						case FACTORY -> factoryRecipes.add(building.recipeId());
						case RESTAURANT -> restaurantRecipes.add(building.recipeId());
					}
				}
				farmRecipes.retainAll(factoryRecipes);
				farmRecipes.retainAll(restaurantRecipes);
				if (!farmRecipes.isEmpty()) {
					unlock(Milestone.VERTICAL_INTEGRATION, now);
					newlyUnlocked.add(Milestone.VERTICAL_INTEGRATION);
				}
			}

			// NEIGHBORHOOD_HERO: ≥ 3 open restaurants at reputation ≥ 0.80.
			if (!unlocked.contains(Milestone.NEIGHBORHOOD_HERO)) {
				int happyCount = 0;
				for (Building building : buildings) {
					if (building instanceof Restaurant restaurant
						&& !restaurant.closed()
						&& restaurant.reputation() >= 0.80) {
						happyCount++;
					}
				}
				if (happyCount >= 3) {
					unlock(Milestone.NEIGHBORHOOD_HERO, now);
					newlyUnlocked.add(Milestone.NEIGHBORHOOD_HERO);
				}
			}

			// CUISINE_MASTER: per-cuisine fulfilled count >= cuisine's total recipe count.
			if (!unlocked.contains(Milestone.CUISINE_MASTER)) {
				for (Map.Entry<String, Long> entry : cuisineSizes.entrySet()) {
					long fulfilled = fulfilledByCuisine.getLong(entry.getKey());
					if (entry.getValue() > 0L && fulfilled >= entry.getValue()) {
						unlock(Milestone.CUISINE_MASTER, now);
						newlyUnlocked.add(Milestone.CUISINE_MASTER);
						break;
					}
				}
			}

			// TRANSIT_TYCOON: count routes used inside the sliding window.
			if (!unlocked.contains(Milestone.TRANSIT_TYCOON)) {
				long cutoff = now - GameConstants.TRANSIT_TYCOON_WINDOW_GAME_MINUTES;
				int liveRoutes = 0;
				for (var entry : recentRouteUsages.object2LongEntrySet()) {
					if (entry.getLongValue() >= cutoff) {
						liveRoutes++;
					}
				}
				if (liveRoutes >= GameConstants.TRANSIT_TYCOON_DISTINCT_ROUTE_TARGET) {
					unlock(Milestone.TRANSIT_TYCOON, now);
					newlyUnlocked.add(Milestone.TRANSIT_TYCOON);
				}
			}

			// CITY_BUILDER soft-win.
			if (!unlocked.contains(Milestone.CITY_BUILDER)
				&& fulfilledCount >= GameConstants.CITY_BUILDER_FULFILLED_ORDER_TARGET) {
				unlock(Milestone.CITY_BUILDER, now);
				newlyUnlocked.add(Milestone.CITY_BUILDER);
			}

			// Surface FIRST_DELIVERY / COLD_CHAIN unlocks too if they happened in
			// recordFulfillment since the last evaluate (they're already added to `unlocked`
			// but not on the per-tick list).
			return newlyUnlocked;
		} finally {
			lock.unlock();
		}
	}

	/** Snapshot of the unlocked set + game-minute timestamps. Returns immutable views. */
	public Snapshot snapshot() {
		lock.lock();
		try {
			return new Snapshot(EnumSet.copyOf(unlocked.isEmpty()
				? EnumSet.noneOf(Milestone.class) : unlocked),
				new EnumMap<>(unlockedAtGameMinutes), fulfilledCount);
		} finally {
			lock.unlock();
		}
	}

	public void reset() {
		lock.lock();
		try {
			unlocked.clear();
			unlockedAtGameMinutes.clear();
			fulfilledCount = 0L;
			fulfilledByCuisine.clear();
			recentRouteUsages.clear();
		} finally {
			lock.unlock();
		}
	}

	/** Suppress kind() reference warnings in switch — used in the per-tick walker scan. */
	@SuppressWarnings("unused")
	private static BuildingKind extractKind(Building building) {
		return building.kind();
	}

	@SuppressWarnings("unused")
	private static boolean isFactoryRefrigerated(Building building) {
		return building instanceof Factory factory && factory.refrigerated();
	}

	private void unlock(Milestone milestone, long gameMinutes) {
		unlocked.add(milestone);
		unlockedAtGameMinutes.put(milestone, gameMinutes);
	}

	public record Snapshot(
		EnumSet<Milestone> unlocked,
		EnumMap<Milestone, Long> unlockedAtGameMinutes,
		long fulfilledCount
	) {
	}
}


