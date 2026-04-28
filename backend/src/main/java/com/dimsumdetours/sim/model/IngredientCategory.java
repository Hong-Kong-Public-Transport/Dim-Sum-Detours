package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A category an ingredient belongs to, e.g. {@code "vegetable"}, {@code "spice"}.
 *
 * <p>Defined by JSON in {@code resources/content/categories/} and overrideable by mods.
 * IDs are lower_snake_case.
 *
 * @param id          Stable identifier, e.g. {@code "vegetable"}.
 * @param displayName Locale tag → player-facing name. Must contain {@code "en"}.
 * @param description Optional locale-tagged longer description.
 */
public record IngredientCategory(
	String id,
	Map<String, String> displayName,
	@Nullable Map<String, String> description
) {

	public @Nullable String localizedName(String locale) {
		return LocalizedText.resolve(displayName, locale);
	}

	public @Nullable String localizedDescription(String locale) {
		return description == null ? null : LocalizedText.resolve(description, locale);
	}
}
