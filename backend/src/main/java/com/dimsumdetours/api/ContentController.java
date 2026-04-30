package com.dimsumdetours.api;

import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Ingredient;
import com.dimsumdetours.sim.model.IngredientCategory;
import com.dimsumdetours.sim.model.Operation;
import com.dimsumdetours.sim.model.Recipe;
import com.dimsumdetours.sim.model.RestaurantTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive content API. The in-memory {@link ContentRegistry} is fast, but we still hop to
 * {@code boundedElastic} so the pattern stays consistent with JPA-backed endpoints.
 */
@RestController
@RequestMapping(path = "/api/content", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ContentController {

	private final ContentRegistry registry;

	// ─── Categories ──────────────────────────────────────────────────────────

	@GetMapping("/categories")
	public Flux<IngredientCategory> listCategories() {
		return Flux.defer(() -> Flux.fromIterable(registry.allCategories()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/categories/{categoryId}")
	public Mono<ResponseEntity<IngredientCategory>> getCategory(@PathVariable String categoryId) {
		return Mono.fromCallable(() -> registry.findCategory(categoryId)
				.<ResponseEntity<IngredientCategory>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	// ─── Operations ──────────────────────────────────────────────────────────

	@GetMapping("/operations")
	public Flux<Operation> listOperations() {
		return Flux.defer(() -> Flux.fromIterable(registry.allOperations()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/operations/{operationId}")
	public Mono<ResponseEntity<Operation>> getOperation(@PathVariable String operationId) {
		return Mono.fromCallable(() -> registry.findOperation(operationId)
				.<ResponseEntity<Operation>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	// ─── Ingredients ─────────────────────────────────────────────────────────

	@GetMapping("/ingredients")
	public Flux<Ingredient> listIngredients() {
		return Flux.defer(() -> Flux.fromIterable(registry.allIngredients()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/ingredients/{ingredientId}")
	public Mono<ResponseEntity<Ingredient>> getIngredient(@PathVariable String ingredientId) {
		return Mono.fromCallable(() -> registry.findIngredient(ingredientId)
				.<ResponseEntity<Ingredient>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	// ─── Recipes ─────────────────────────────────────────────────────────────

	@GetMapping("/recipes")
	public Flux<Recipe> listRecipes() {
		return Flux.defer(() -> Flux.fromIterable(registry.allRecipes()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/recipes/{recipeId}")
	public Mono<ResponseEntity<Recipe>> getRecipe(@PathVariable String recipeId) {
		return Mono.fromCallable(() -> registry.findRecipe(recipeId)
				.<ResponseEntity<Recipe>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	// ─── Restaurant templates ────────────────────────────────────────────────

	@GetMapping("/restaurants")
	public Flux<RestaurantTemplate> listRestaurantTemplates() {
		return Flux.defer(() -> Flux.fromIterable(registry.allRestaurantTemplates()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/restaurants/{templateId}")
	public Mono<ResponseEntity<RestaurantTemplate>> getRestaurantTemplate(@PathVariable String templateId) {
		return Mono.fromCallable(() -> registry.findRestaurantTemplate(templateId)
				.<ResponseEntity<RestaurantTemplate>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build()))
			.subscribeOn(Schedulers.boundedElastic());
	}
}
