package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A processing recipe: a sequence of {@link Operation}s a factory applies to a set of input
 * ingredients to produce output ingredients.
 *
 * <p>Defined by JSON in {@code resources/content/recipes/} and overrideable by mods.
 * Framework-agnostic — keep it that way.
 *
 * <p>Localisation lives <em>inline</em> in {@link #displayName} so each mod is self-contained.
 * The English entry is mandatory.
 *
 * <p><strong>Validity</strong> (enforced by {@code ContentLoader}):
 * <ul>
 *   <li>Every {@code ingredientId} in {@code inputs} and {@code outputs} must exist in the
 *       {@code ContentRegistry}.</li>
 *   <li>Every entry in {@code operations} must reference a registered {@link Operation} id.</li>
 *   <li>{@code operations} must be non-empty.</li>
 *   <li>{@code minimumFactoryTier} is 1-indexed (1 = T1).</li>
 * </ul>
 *
 * @param id                       Stable identifier, e.g. {@code "garlic_salt"}.
 * @param displayName              Locale tag → player-facing name. Must contain {@code "en"}.
 * @param inputs                   Ingredients consumed per execution.
 * @param operations               Ordered list of {@link Operation} {@code id}s applied to the inputs.
 * @param outputs                  Ingredients produced per execution.
 * @param minimumFactoryTier       Lowest factory tier capable of running this recipe.
 * @param operationDurationMinutes Total game-minutes one execution takes.
 * @param tags                     Free-form tags ({@code "dish"}, {@code "intermediate"}, …).
 */
public record Recipe(
	String id,
	Map<String, String> displayName,
	List<RecipeIngredient> inputs,
	List<String> operations,
	List<RecipeIngredient> outputs,
	int minimumFactoryTier,
	int operationDurationMinutes,
	List<String> tags
) {

	/**
	 * Mandatory fallback locale; mirrors {@link LocalizedText#FALLBACK_LOCALE}.
	 */
	public static final String FALLBACK_LOCALE = LocalizedText.FALLBACK_LOCALE;

	public @Nullable String localizedName(String locale) {
		return LocalizedText.resolve(displayName, locale);
	}
}
