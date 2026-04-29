package com.dimsumdetours.sim.state;

import com.dimsumdetours.config.GameConstants;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Farm;
import com.dimsumdetours.sim.model.GameClock;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RecipeIngredient;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authoritative in-memory game state for Phase 3: balance + placed buildings.
 *
 * <p>Framework-agnostic (no Spring imports — see {@code sim/} package contract). Thread-safe
 * via a single {@link ReentrantLock}; all mutating operations decide their reads/writes under
 * the same lock so the controller can call them from any reactive scheduler.
 *
 * <p>Persistence is deliberately out-of-scope here — Phase 6 ("is it fun?") is the right time
 * to add a JPA-backed repo. The state is rebuilt from {@link GameConstants#STARTING_BALANCE}
 * on every backend restart for now.
 */
public final class GameState {

	private final ContentRegistry registry;
	private final ReentrantLock lock = new ReentrantLock();

	// Ordered for predictable list responses; matches the ContentRegistry style.
	private final Object2ObjectMap<UUID, Building> buildings = new Object2ObjectLinkedOpenHashMap<>();
	private Money balance;
	private final GameClock clock = new GameClock();

	public GameState(ContentRegistry registry) {
		this(registry, Money.of(GameConstants.STARTING_BALANCE));
	}

	/** Test-friendly constructor. */
	public GameState(ContentRegistry registry, Money startingBalance) {
		this.registry = registry;
		this.balance = startingBalance;
	}

	public Money getBalance() {
		lock.lock();
		try {
			return balance;
		} finally {
			lock.unlock();
		}
	}

	public List<Building> listBuildings() {
		lock.lock();
		try {
			return new ArrayList<>(buildings.values());
		} finally {
			lock.unlock();
		}
	}

	public PlacementResult placeBuilding(BuildingKind kind, double lat, double lon, String recipeId) {
		if (!Double.isFinite(lat) || !Double.isFinite(lon)
			|| lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
			return new PlacementResult.Failure(PlacementError.INVALID_COORDINATES);
		}

		Optional<Recipe> maybeRecipe = registry.findRecipe(recipeId);
		if (maybeRecipe.isEmpty()) {
			return new PlacementResult.Failure(PlacementError.UNKNOWN_RECIPE);
		}
		Recipe recipe = maybeRecipe.get();

		// Phase 3 only opens T1 recipes for placement; higher tiers gate behind progression.
		if (recipe.minimumFactoryTier() > 1) {
			return new PlacementResult.Failure(PlacementError.RECIPE_TIER_TOO_HIGH);
		}
		if (recipe.outputs().isEmpty()) {
			return new PlacementResult.Failure(PlacementError.RECIPE_HAS_NO_OUTPUTS);
		}

		Money cost = kind.buildCost();

		lock.lock();
		try {
			if (!balance.isAtLeast(cost)) {
				return new PlacementResult.Failure(PlacementError.INSUFFICIENT_FUNDS);
			}
			Building building = switch (kind) {
				case FARM -> {
					RecipeIngredient firstOutput = recipe.outputs().get(0);
					yield new Farm(UUID.randomUUID(), lat, lon, recipeId, firstOutput.ingredientId());
				}
				case FACTORY -> new Factory(UUID.randomUUID(), lat, lon, recipeId, recipe.operations());
			};
			balance = balance.minus(cost);
			buildings.put(building.id(), building);
			return new PlacementResult.Success(building, balance);
		} finally {
			lock.unlock();
		}
	}

	public Optional<Building> demolishBuilding(UUID id) {
		lock.lock();
		try {
			return Optional.ofNullable(buildings.remove(id));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Phase 5: reorder a factory's operations. Returns the updated building, or empty if no
	 * such factory exists. Throws {@link IllegalArgumentException} if the new list is not a
	 * permutation of the existing one.
	 */
	public Optional<Factory> reorderFactoryOperations(UUID id, List<String> newOperations) {
		lock.lock();
		try {
			Building existing = buildings.get(id);
			if (!(existing instanceof Factory factory)) {
				return Optional.empty();
			}
			Factory updated = factory.withOperations(newOperations);
			buildings.put(id, updated);
			return Optional.of(updated);
		} finally {
			lock.unlock();
		}
	}

	/** Test helper. */
	public void reset() {
		lock.lock();
		try {
			buildings.clear();
			balance = Money.of(GameConstants.STARTING_BALANCE);
			clock.reset();
		} finally {
			lock.unlock();
		}
	}

	// ─── Phase 4: clock access ────────────────────────────────────────────

	public ClockSnapshot getClockSnapshot() {
		lock.lock();
		try {
			return new ClockSnapshot(
				clock.getGameMinutes(),
				clock.getDayOfWeek(),
				clock.getMinuteOfDay(),
				clock.getSpeedMultiplier(),
				!clock.isPaused());
		} finally {
			lock.unlock();
		}
	}

	public void setClockSpeed(int speed) {
		lock.lock();
		try {
			clock.setSpeedMultiplier(speed);
		} finally {
			lock.unlock();
		}
	}

	public void setClockPaused(boolean paused) {
		lock.lock();
		try {
			clock.setPaused(paused);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Advance the clock by {@code deltaGameMinutes}. Called from the simulation engine each tick.
	 */
	public void advanceClock(long deltaGameMinutes) {
		if (deltaGameMinutes <= 0) {
			return;
		}
		lock.lock();
		try {
			clock.advance(deltaGameMinutes);
		} finally {
			lock.unlock();
		}
	}

	/** Snapshot record returned to the API/SSE layer. */
	public record ClockSnapshot(long gameMinutes, int dayOfWeek, int minuteOfDay, int speed, boolean playing) {
	}
}

