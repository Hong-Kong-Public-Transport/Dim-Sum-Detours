package com.dimsumdetours.content;

import com.dimsumdetours.config.DimSumDetoursProperties;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

/**
 * Populates the {@link ContentRegistry} from classpath JSON and (optionally) player mods.
 *
 * <p>Load order matters because of referential validation:
 * <ol>
 *   <li>Ingredient categories (no dependencies)</li>
 *   <li>Operations (no dependencies)</li>
 *   <li>Ingredients (validate {@code category})</li>
 *   <li>Recipes (validate {@code inputs}/{@code outputs} against ingredients,
 *       and {@code operations} against the operation registry)</li>
 * </ol>
 *
 * <p>Mod folders under {@code data/mods/<mod-name>/} are loaded <em>after</em> built-in content,
 * so a mod entry with the same {@code id} replaces the built-in one.
 *
 * <p>Failures log a warning and skip the offending file rather than crashing the application.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentLoader {

	private static final String BUILTIN_CONTENT_BASE = "classpath:content";

	private final ContentRegistry registry;
	private final DimSumDetoursProperties properties;
	private final ObjectMapper objectMapper;
	private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	@EventListener(ApplicationReadyEvent.class)
	public void loadAll() {
		registry.clear();

		loadBuiltinCategories();
		loadBuiltinOperations();
		loadBuiltinIngredients();
		loadBuiltinRecipes();

		loadMods();

		log.info(
			"Content loaded: {} categories, {} operations, {} ingredients, {} recipes.",
			registry.categoryCount(),
			registry.operationCount(),
			registry.ingredientCount(),
			registry.recipeCount());
	}

	// ─── Built-in classpath loaders ──────────────────────────────────────────

	private void loadBuiltinCategories() {
		for (Resource resource : resolveClasspath("categories")) {
			readJson(resource, IngredientCategory.class).ifPresent(category -> {
				if (validateLocalisedName(category.id(), category.displayName())) {
					registry.putCategory(category);
				}
			});
		}
	}

	private void loadBuiltinOperations() {
		for (Resource resource : resolveClasspath("operations")) {
			readJson(resource, Operation.class).ifPresent(operation -> {
				if (validateLocalisedName(operation.id(), operation.displayName())) {
					registry.putOperation(operation);
				}
			});
		}
	}

	private void loadBuiltinIngredients() {
		for (Resource resource : resolveClasspath("ingredients")) {
			readJson(resource, Ingredient.class).ifPresent(this::tryPutIngredient);
		}
	}

	private void loadBuiltinRecipes() {
		for (Resource resource : resolveClasspath("recipes")) {
			readJson(resource, Recipe.class).ifPresent(this::tryPutRecipe);
		}
	}

	private List<Resource> resolveClasspath(String subfolder) {
		String pattern = BUILTIN_CONTENT_BASE + "/" + subfolder + "/*.json";
		try {
			return List.of(resolver.getResources(pattern));
		} catch (IOException exception) {
			log.warn("Failed to resolve content pattern {}: {}", pattern, exception.getMessage());
			return Collections.emptyList();
		}
	}

	// ─── Mod loader (filesystem) ─────────────────────────────────────────────

	private void loadMods() {
		String configured = properties.getModsDir();
		if (StringUtils.isBlank(configured)) {
			return;
		}
		File modsRoot = new File(configured).getAbsoluteFile();
		if (!modsRoot.isDirectory()) {
			log.debug("Mods directory absent (this is fine): {}", modsRoot.getAbsolutePath());
			return;
		}
		File[] modDirectories = modsRoot.listFiles(File::isDirectory);
		if (modDirectories == null || modDirectories.length == 0) {
			return;
		}
		for (File modDirectory : modDirectories) {
			log.info("Loading mod: {}", modDirectory.getName());
			loadModSubfolder(modDirectory, "categories", IngredientCategory.class,
				category -> {
					if (validateLocalisedName(category.id(), category.displayName())) {
						registry.putCategory(category);
					}
				});
			loadModSubfolder(modDirectory, "operations", Operation.class,
				operation -> {
					if (validateLocalisedName(operation.id(), operation.displayName())) {
						registry.putOperation(operation);
					}
				});
			loadModSubfolder(modDirectory, "ingredients", Ingredient.class, this::tryPutIngredient);
			loadModSubfolder(modDirectory, "recipes", Recipe.class, this::tryPutRecipe);
		}
	}

	private <T> void loadModSubfolder(File modDirectory, String subfolder,
	                                  Class<T> type, Consumer<T> sink) {
		File subdir = new File(modDirectory, subfolder);
		if (!subdir.isDirectory()) {
			return;
		}
		Collection<File> files = FileUtils.listFiles(subdir, new String[]{"json"}, false);
		for (File file : files) {
			try (InputStream stream = FileUtils.openInputStream(file)) {
				T parsed = objectMapper.readValue(stream, type);
				sink.accept(parsed);
			} catch (IOException exception) {
				log.warn("Failed to read mod file {}: {}", file, exception.getMessage());
			}
		}
	}

	// ─── Validation + register ───────────────────────────────────────────────

	private void tryPutIngredient(Ingredient ingredient) {
		if (!validateLocalisedName(ingredient.id(), ingredient.displayName())) {
			return;
		}
		if (StringUtils.isBlank(ingredient.category())
			|| !registry.hasCategory(ingredient.category())) {
			log.warn("Ingredient '{}' references unknown category '{}'; skipping.",
				ingredient.id(), ingredient.category());
			return;
		}
		registry.putIngredient(ingredient);
	}

	private void tryPutRecipe(Recipe recipe) {
		if (!validateLocalisedName(recipe.id(), recipe.displayName())) {
			return;
		}
		if (recipe.operations().isEmpty()) {
			log.warn("Recipe '{}' has no operations; skipping.", recipe.id());
			return;
		}
		for (String operationId : recipe.operations()) {
			if (!registry.hasOperation(operationId)) {
				log.warn("Recipe '{}' references unknown operation '{}'; skipping.",
					recipe.id(), operationId);
				return;
			}
		}
		// Outputs are mandatory: a recipe that produces nothing is meaningless. Inputs are
		// optional — farm/harvest recipes (e.g. grow_garlic, harvest_salt) take nothing and
		// produce a raw ingredient.
		if (recipe.outputs().isEmpty()) {
			log.warn("Recipe '{}' must have at least one output; skipping.", recipe.id());
			return;
		}
		for (RecipeIngredient input : recipe.inputs()) {
			if (!registry.hasIngredient(input.ingredientId())) {
				log.warn("Recipe '{}' references unknown input ingredient '{}'; skipping.",
					recipe.id(), input.ingredientId());
				return;
			}
		}
		for (RecipeIngredient output : recipe.outputs()) {
			if (!registry.hasIngredient(output.ingredientId())) {
				log.warn("Recipe '{}' references unknown output ingredient '{}'; skipping.",
					recipe.id(), output.ingredientId());
				return;
			}
		}
		registry.putRecipe(recipe);
	}

	private boolean validateLocalisedName(String id, Map<String, String> displayName) {
		if (StringUtils.isBlank(id)) {
			log.warn("Content entry rejected: missing id.");
			return false;
		}
		if (StringUtils.isBlank(displayName.get(LocalizedText.FALLBACK_LOCALE))) {
			log.warn("Content entry '{}' rejected: missing mandatory '{}' displayName.",
				id, LocalizedText.FALLBACK_LOCALE);
			return false;
		}
		return true;
	}

	private <T> Optional<T> readJson(Resource resource, Class<T> type) {
		try (InputStream stream = resource.getInputStream()) {
			return Optional.of(objectMapper.readValue(stream, type));
		} catch (IOException exception) {
			log.warn("Failed to read {}: {}", resource.getFilename(), exception.getMessage());
			return Optional.empty();
		}
	}
}
