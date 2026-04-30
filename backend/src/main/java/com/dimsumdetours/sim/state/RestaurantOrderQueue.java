package com.dimsumdetours.sim.state;

import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderResult;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.UUID;

/**
 * Per-restaurant queue of pending orders. Held outside {@link com.dimsumdetours.sim.model.Restaurant}
 * so the record stays a pure value type — the queue is mutable engine state.
 *
 * <p>Backed by a {@code LinkedOpenHashMap} for predictable iteration order (oldest order first)
 * + O(1) removal by id. Not thread-safe on its own — callers ({@link GameState}) hold a lock.
 */
public final class RestaurantOrderQueue {

	private final Object2ObjectMap<UUID, Order> orders = new Object2ObjectLinkedOpenHashMap<>();

	public void enqueue(Order order) {
		orders.put(order.id(), order);
	}

	public List<Order> snapshot() {
		return new ObjectArrayList<>(orders.values());
	}

	public boolean isEmpty() {
		return orders.isEmpty();
	}

	/**
	 * Mark an order as delivered. Returns {@link OrderResult#FULFILLED} if before the deadline,
	 * {@link OrderResult#LATE} if after, or {@code null} if the id is unknown.
	 */
	public OrderResult fulfill(UUID orderId, long currentGameMinutes) {
		Order order = orders.remove(orderId);
		if (order == null) {
			return null;
		}
		return currentGameMinutes <= order.deadlineGameMinutes() ? OrderResult.FULFILLED : OrderResult.LATE;
	}

	/**
	 * Drop every order whose deadline is at or before {@code currentGameMinutes}; return the
	 * expired ones so the engine can apply reputation hits.
	 */
	public List<Order> expireUpTo(long currentGameMinutes) {
		ObjectArrayList<Order> expired = new ObjectArrayList<>();
		var iterator = orders.values().iterator();
		while (iterator.hasNext()) {
			Order order = iterator.next();
			if (order.deadlineGameMinutes() <= currentGameMinutes) {
				expired.add(order);
				iterator.remove();
			}
		}
		return expired;
	}

	public void clear() {
		orders.clear();
	}
}

