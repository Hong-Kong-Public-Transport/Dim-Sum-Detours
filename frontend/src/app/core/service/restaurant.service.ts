import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";

import {map, Observable, tap} from "rxjs";

/** Mirrors {@code GameController.OrderDto} on the backend. */
export interface Order {
	readonly id: string;
	readonly restaurantId: string;
	readonly recipeId: string;
	readonly quantity: number;
	readonly createdAtGameMinutes: number;
	readonly deadlineGameMinutes: number;
}

export interface EnqueueOrderRequest {
	readonly recipeId: string;
	readonly quantity: number;
	readonly patienceGameMinutes: number;
}

/** Mirrors {@code GameController.FulfillOrderResponse}. */
export interface FulfillOrderResponse {
	readonly result: "FULFILLED" | "LATE" | "EXPIRED" | "SPOILED";
	readonly payout: number;
	readonly newBalance: number;
	readonly newReputation: number;
}

/** Mirrors the discriminator on {@code OrderEvent} on the backend. */
export type OrderEvent =
	| {readonly type: "ENQUEUED"; readonly order: Order; readonly gameMinutes: number}
	| {
		readonly type: "FULFILLED";
		readonly orderId: string;
		readonly restaurantId: string;
		readonly result: "FULFILLED" | "LATE";
		readonly payout: number;
		readonly newBalance: number;
		readonly newReputation: number;
		readonly gameMinutes: number;
	}
	| {
		readonly type: "EXPIRED";
		readonly orderId: string;
		readonly restaurantId: string;
		readonly newReputation: number;
		readonly gameMinutes: number;
	};

/**
 * Phase-6 frontend mirror of restaurant orders. Holds an authoritative copy of the open
 * order list as a signal, kept in sync with the backend via:
 * <ul>
 *   <li>One initial fetch via {@link refreshOrders}.</li>
 *   <li>An {@link EventSource} on {@code /api/game/orders/stream} that pushes
 *       {@link OrderEvent}s as they happen.</li>
 *   <li>Direct mutations from {@link enqueueOrder} / {@link fulfillOrder} (the SSE event
 *       arrives moments later and is idempotent).</li>
 * </ul>
 */
@Injectable({providedIn: "root"})
export class RestaurantService {
	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);

	private readonly _orders = signal<readonly Order[]>([]);
	private readonly _lastEvent = signal<OrderEvent | null>(null);

	readonly orders = this._orders.asReadonly();
	/** Most recently received {@link OrderEvent}; useful for one-shot toasts. */
	readonly lastEvent = this._lastEvent.asReadonly();

	private eventSource?: EventSource;

	constructor() {
		this.openStream();
		this.destroyRef.onDestroy(() => this.eventSource?.close());
	}

	refreshOrders(): Observable<readonly Order[]> {
		return this.httpClient.get<Order[]>("/api/game/orders").pipe(
			tap((list) => this._orders.set(list)),
		);
	}

	listOrdersForRestaurant(restaurantId: string): Observable<readonly Order[]> {
		const path = `/api/game/restaurants/${encodeURIComponent(restaurantId)}/orders`;
		return this.httpClient.get<Order[]>(path);
	}

	enqueueOrder(restaurantId: string, request: EnqueueOrderRequest): Observable<Order> {
		const path = `/api/game/restaurants/${encodeURIComponent(restaurantId)}/orders`;
		return this.httpClient.post<Order>(path, request).pipe(
			tap((order) => this._orders.update((list) =>
				list.some((existing) => existing.id === order.id) ? list : [...list, order],
			)),
		);
	}

	fulfillOrder(restaurantId: string, orderId: string): Observable<FulfillOrderResponse> {
		const path = `/api/game/restaurants/${encodeURIComponent(restaurantId)}`
			+ `/orders/${encodeURIComponent(orderId)}/fulfill`;
		return this.httpClient.post<FulfillOrderResponse>(path, {}).pipe(
			tap(() => this._orders.update((list) => list.filter((order) => order.id !== orderId))),
			map((response) => response),
		);
	}

	/**
	 * Phase-7: report a spoiled-in-transit cargo. The backend applies the "missed delivery"
	 * reputation hit, no payout. Symmetric to {@link fulfillOrder} so callers can swap one
	 * for the other without re-plumbing the signal updates.
	 */
	spoilOrder(restaurantId: string, orderId: string): Observable<FulfillOrderResponse> {
		const path = `/api/game/restaurants/${encodeURIComponent(restaurantId)}`
			+ `/orders/${encodeURIComponent(orderId)}/spoil`;
		return this.httpClient.post<FulfillOrderResponse>(path, {}).pipe(
			tap(() => this._orders.update((list) => list.filter((order) => order.id !== orderId))),
			map((response) => response),
		);
	}

	/** Orders held against a specific restaurant. Cheap derivation off the signal. */
	ordersFor(restaurantId: string): readonly Order[] {
		return this._orders().filter((order) => order.restaurantId === restaurantId);
	}

	private openStream(): void {
		try {
			this.eventSource = new EventSource("/api/game/orders/stream");
			this.eventSource.onmessage = (event) => {
				try {
					const parsed = JSON.parse(event.data) as OrderEvent;
					if (typeof parsed.type !== "string") {
						// Phase-13 regression guard: same lesson as VehicleService — a
						// missing `type` discriminator means every order frame is silently
						// dropped and the restaurant drawer appears empty.
						console.error("order stream frame missing 'type' discriminator", event.data);
						return;
					}
					this.applyEvent(parsed);
					this._lastEvent.set(parsed);
				} catch (err) {
					console.error("order stream frame failed to parse", err, event.data);
				}
			};
			this.eventSource.onerror = (err) => {
				console.warn("order stream error (browser will auto-reconnect)", err);
			};
		} catch (err) {
			console.error("order stream failed to open", err);
		}
	}

	private applyEvent(event: OrderEvent): void {
		switch (event.type) {
			case "ENQUEUED":
				this._orders.update((list) =>
					list.some((existing) => existing.id === event.order.id) ? list : [...list, event.order],
				);
				return;
			case "FULFILLED":
			case "EXPIRED":
				this._orders.update((list) => list.filter((order) => order.id !== event.orderId));
				return;
		}
	}
}
