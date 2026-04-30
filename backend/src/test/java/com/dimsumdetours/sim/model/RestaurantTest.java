package com.dimsumdetours.sim.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase-6 unit tests for the {@link Restaurant} value type. Pure record behaviour — no
 * {@code GameState} or Spring scaffolding — so we can pin the reputation-clamping rules and
 * the constructor invariants in isolation.
 */
class RestaurantTest {

	@Test
	void of_freshRestaurantStartsAtFullReputation() {
		Restaurant restaurant = Restaurant.of(UUID.randomUUID(), 47.6, -122.3, "garlic_rice");
		assertEquals(1.0, restaurant.reputation());
		assertEquals(BuildingKind.RESTAURANT, restaurant.kind());
		assertNull(restaurant.templateId());
	}

	@Test
	void of_withTemplateRetainsTemplateId() {
		Restaurant restaurant = Restaurant.of(UUID.randomUUID(), 47.6, -122.3, "garlic_rice", "dim_sum_house");
		assertEquals("dim_sum_house", restaurant.templateId());
	}

	@Test
	void withReputation_clampsBelowZero() {
		Restaurant restaurant = Restaurant.of(UUID.randomUUID(), 47.6, -122.3, "garlic_rice");
		Restaurant updated = restaurant.withReputation(-0.5);
		assertEquals(0.0, updated.reputation());
	}

	@Test
	void withReputation_clampsAboveOne() {
		Restaurant restaurant = Restaurant.of(UUID.randomUUID(), 47.6, -122.3, "garlic_rice");
		Restaurant updated = restaurant.withReputation(1.5);
		assertEquals(1.0, updated.reputation());
	}

	@Test
	void constructor_rejectsOutOfRangeReputation() {
		UUID id = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class,
			() -> new Restaurant(id, 47.6, -122.3, "garlic_rice", -0.1, null));
		assertThrows(IllegalArgumentException.class,
			() -> new Restaurant(id, 47.6, -122.3, "garlic_rice", 1.1, null));
	}

	@Test
	void withReputation_preservesIdentityFields() {
		UUID id = UUID.randomUUID();
		Restaurant restaurant = new Restaurant(id, 47.6, -122.3, "garlic_rice", 0.7, "dim_sum_house");
		Restaurant updated = restaurant.withReputation(0.4);
		assertNotNull(updated);
		assertEquals(id, updated.id());
		assertEquals(47.6, updated.lat());
		assertEquals(-122.3, updated.lon());
		assertEquals("garlic_rice", updated.recipeId());
		assertEquals("dim_sum_house", updated.templateId());
		assertEquals(0.4, updated.reputation());
	}
}

