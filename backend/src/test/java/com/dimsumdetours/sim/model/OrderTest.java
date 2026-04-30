package com.dimsumdetours.sim.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-6 unit tests for the {@link Order} value type.
 */
class OrderTest {

	private static Order sample(long createdAt, long deadline) {
		return new Order(UUID.randomUUID(), UUID.randomUUID(), "garlic_rice", 1, createdAt, deadline);
	}

	@Test
	void constructor_rejectsNonPositiveQuantity() {
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class,
			() -> new Order(orderId, restaurantId, "garlic_rice", 0, 0L, 60L));
		assertThrows(IllegalArgumentException.class,
			() -> new Order(orderId, restaurantId, "garlic_rice", -3, 0L, 60L));
	}

	@Test
	void constructor_rejectsDeadlineAtOrBeforeCreation() {
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class,
			() -> new Order(orderId, restaurantId, "garlic_rice", 1, 100L, 100L));
		assertThrows(IllegalArgumentException.class,
			() -> new Order(orderId, restaurantId, "garlic_rice", 1, 100L, 50L));
	}

	@Test
	void remainingMinutes_isPositiveBeforeDeadline() {
		Order order = sample(0L, 60L);
		assertEquals(60L, order.remainingMinutes(0L));
		assertEquals(15L, order.remainingMinutes(45L));
	}

	@Test
	void remainingMinutes_isNegativePastDeadline() {
		Order order = sample(0L, 60L);
		assertTrue(order.remainingMinutes(75L) < 0);
	}
}

