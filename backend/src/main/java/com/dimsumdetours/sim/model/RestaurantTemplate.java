package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * JSON-defined content describing a kind of restaurant the simulation can spawn or a player
 * can place. Distinct from {@link Restaurant}, which represents a placed instance on the map.
 *
 * <p>Lives in `backend/src/main/resources/content/restaurants/*.json` and is loaded by
 * {@code ContentLoader}. Modders extend the catalogue by dropping JSON into
 * {@code data/mods/<mod>/restaurants/}.
 *
 * @param id                   stable, lower_snake_case identifier (e.g. {@code dim_sum_house}).
 * @param displayName          per-locale display name (the {@code en} entry is mandatory).
 * @param acceptedRecipeIds    recipes this restaurant can receive deliveries of. Validated to
 *                             reference real recipes at load time.
 * @param basePatienceMinutes  default order patience window, in game-minutes.
 * @param basePayout           base reward in {@link Money} units for an on-time delivery.
 * @param tags                 free-form descriptors (cuisine, tier, …) for filtering.
 */
public record RestaurantTemplate(
	String id,
	Map<String, String> displayName,
	List<String> acceptedRecipeIds,
	long basePatienceMinutes,
	long basePayout,
	@Nullable List<String> tags
) {

	public RestaurantTemplate {
		if (basePatienceMinutes <= 0) {
			throw new IllegalArgumentException("basePatienceMinutes must be positive, got " + basePatienceMinutes);
		}
		if (basePayout < 0) {
			throw new IllegalArgumentException("basePayout must be non-negative, got " + basePayout);
		}
	}
}

