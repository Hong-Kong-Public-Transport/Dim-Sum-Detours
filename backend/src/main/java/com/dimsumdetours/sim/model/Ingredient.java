package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A raw or processed input that flows through the supply chain.
 *
 * <p>Defined by JSON in {@code resources/content/ingredients/} and overrideable by mods.
 * Framework-agnostic — keep it that way.
 *
 * <p>Localisation lives <em>inline</em> in {@link #displayName} so each mod is self-contained.
 * The English entry is mandatory and acts as the fallback for missing locales.
 *
 * @param id               Stable identifier, e.g. {@code "garlic"}.
 * @param displayName      Locale tag → player-facing name. Must contain {@code "en"}.
 * @param category         Reference to an {@link IngredientCategory} {@code id} (lower_snake_case).
 * @param shelfLifeMinutes Game-minutes before it spoils. {@code -1} means non-perishable.
 * @param refrigeratable   If true, refrigerated factories pause the spoilage timer.
 * @param baseValue        Reference market value, used for buy/sell pricing.
 * @param tags             Free-form tags ({@code "perishable"}, {@code "seafood"}, …).
 *                         Convention: lower_snake_case.
 */
public record Ingredient(
	String id,
	Map<String, String> displayName,
	String category,
	int shelfLifeMinutes,
	boolean refrigeratable,
	long baseValue,
	List<String> tags
) {

	/**
	 * Mandatory fallback locale; mirrors {@link LocalizedText#FALLBACK_LOCALE}.
	 */
	public static final String FALLBACK_LOCALE = LocalizedText.FALLBACK_LOCALE;

	public boolean isPerishable() {
		return shelfLifeMinutes > 0;
	}

	public @Nullable String localizedName(String locale) {
		return LocalizedText.resolve(displayName, locale);
	}
}
