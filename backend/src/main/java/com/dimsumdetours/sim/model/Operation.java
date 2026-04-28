package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A processing step a factory can perform on ingredients
 * (e.g. {@code "steam"}, {@code "dehydrate"}, {@code "powderize"}).
 *
 * <p>Defined by JSON in {@code resources/content/operations/} and overrideable by mods.
 * IDs are lower_snake_case so the player and modders can read them in stack traces and
 * recipe JSON without surprises.
 *
 * @param id          Stable identifier, e.g. {@code "steam"}.
 * @param displayName Locale tag → player-facing name. Must contain {@code "en"}.
 * @param description Optional locale-tagged longer description.
 */
public record Operation(
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
