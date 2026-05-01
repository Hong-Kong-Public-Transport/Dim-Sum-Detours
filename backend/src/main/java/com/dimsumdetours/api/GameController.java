package com.dimsumdetours.api;

import com.dimsumdetours.engine.OrderGenerator;
import com.dimsumdetours.engine.SimulationEngine;
import com.dimsumdetours.sim.content.ContentRegistry;
import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Milestone;
import com.dimsumdetours.sim.model.MilestoneEvent;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.Order;
import com.dimsumdetours.sim.model.OrderEvent;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.model.Restaurant;
import com.dimsumdetours.sim.model.vehicle.Robot;
import com.dimsumdetours.sim.model.vehicle.Vehicle;
import com.dimsumdetours.sim.model.vehicle.VehicleEvent;
import com.dimsumdetours.sim.state.GameState;
import com.dimsumdetours.sim.state.MilestoneTracker;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 endpoints: read the player's wallet, list owned buildings, place new ones, demolish.
 *
 * <p>Work is in-memory and very cheap, so we keep it on the calling thread (no
 * {@code subscribeOn(boundedElastic())}). The reactive return types are kept for consistency
 * with the rest of the API surface and to make the eventual JPA migration painless.
 */
@RestController
@RequestMapping(path = "/api/game", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GameController {

	private final GameState gameState;
	private final SimulationEngine engine;
	private final OrderGenerator orderGenerator;
	private final ContentRegistry registry;

	@GetMapping("/balance")
	public Mono<BalanceResponse> getBalance() {
		return Mono.fromSupplier(() -> new BalanceResponse(gameState.getBalance().amount()));
	}

	@GetMapping("/buildings")
	public Mono<List<BuildingDto>> listBuildings() {
		return Mono.fromSupplier(() -> gameState.listBuildings().stream()
			.map(b -> BuildingDto.from(b, registry))
			.toList());
	}

	@PostMapping("/buildings")
	public Mono<ResponseEntity<PlaceBuildingResponse>> placeBuilding(@RequestBody PlaceBuildingRequest request) {
		return Mono.fromSupplier(() -> {
			BuildingKind kind;
			try {
				kind = BuildingKind.valueOf(request.kind().toUpperCase());
			} catch (IllegalArgumentException ex) {
				return ResponseEntity.badRequest().body(
					new PlaceBuildingResponse(null, null, "UNKNOWN_BUILDING_KIND", null));
			}

			PlacementResult result = gameState.placeBuilding(
				kind, request.lat(), request.lon(), request.recipeId(), request.templateId());
			if (result instanceof PlacementResult.Success success) {
				BuildingDto dto = BuildingDto.from(success.building(), registry);
				return ResponseEntity
					.created(URI.create("/api/game/buildings/" + dto.id()))
					.body(new PlaceBuildingResponse(dto, success.newBalance().amount(), null, null));
			}
			PlacementError error = ((PlacementResult.Failure) result).error();
			HttpStatus status = (error == PlacementError.INSUFFICIENT_FUNDS)
				? HttpStatus.PAYMENT_REQUIRED
				: HttpStatus.BAD_REQUEST;
			Long required = (error == PlacementError.INSUFFICIENT_FUNDS) ? kind.buildCost().amount() : null;
			Money current = gameState.getBalance();
			return ResponseEntity.status(status).body(
				new PlaceBuildingResponse(null, current.amount(), error.name(), required));
		});
	}

	@DeleteMapping("/buildings/{id}")
	public Mono<ResponseEntity<Void>> demolishBuilding(@PathVariable UUID id) {
		return Mono.fromSupplier(() -> gameState.demolishBuilding(id).isPresent()
			? ResponseEntity.noContent().<Void>build()
			: ResponseEntity.notFound().<Void>build());
	}

	/**
	 * Phase 5: reorder the operation chain on a placed factory. Body is the new ordered list of
	 * operation ids; must be a permutation of the existing list.
	 */
	@PutMapping("/buildings/{id}/operations")
	public Mono<ResponseEntity<BuildingDto>> reorderOperations(
		@PathVariable UUID id,
		@RequestBody ReorderOperationsRequest request
	) {
		return Mono.fromSupplier(() -> {
			try {
				return gameState.reorderFactoryOperations(id, request.operations())
					.map(factory -> ResponseEntity.ok(BuildingDto.from(factory, registry)))
					.orElseGet(() -> ResponseEntity.notFound().build());
			} catch (IllegalArgumentException ex) {
				return ResponseEntity.badRequest().<BuildingDto>build();
			}
		});
	}


	/**
	 * Phase-8 task 6: spend the refrigeration upgrade fee on a placed factory. 200 with the
	 * updated DTO on success, 402 PAYMENT_REQUIRED if broke, 404 if the id doesn't belong to
	 * a factory. Idempotent: an already-refrigerated factory returns 200 with no charge.
	 */
	@PostMapping("/buildings/{id}/refrigerate")
	public Mono<ResponseEntity<BuildingDto>> refrigerateFactory(@PathVariable UUID id) {
		return Mono.fromSupplier(() -> {
			try {
				return gameState.tryUpgradeFactoryRefrigeration(id)
					.map(factory -> ResponseEntity.ok(BuildingDto.from(factory, registry)))
					.orElseGet(() -> ResponseEntity.notFound().build());
			} catch (IllegalStateException ex) {
				return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).<BuildingDto>build();
			}
		});
	}

	// ─── Phase 8 task 7: milestones ────────────────────────────────────────

	@GetMapping("/milestones")
	public Mono<MilestonesResponse> listMilestones() {
		return Mono.fromSupplier(() -> {
			MilestoneTracker.Snapshot snapshot = engine.milestoneTracker().snapshot();
			List<MilestoneDto> list = new java.util.ArrayList<>();
			for (Milestone milestone : Milestone.values()) {
				boolean unlocked = snapshot.unlocked().contains(milestone);
				Long unlockedAt = snapshot.unlockedAtGameMinutes().get(milestone);
				list.add(new MilestoneDto(milestone.name(), unlocked, unlockedAt));
			}
			return new MilestonesResponse(list, snapshot.fulfilledCount());
		});
	}

	@GetMapping(path = "/milestones/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<MilestoneEvent> milestoneStream() {
		return engine.milestoneStream();
	}

	// ─── Phase 12: vehicles (robots) ──────────────────────────────────────

	@GetMapping("/vehicles")
	public Mono<List<VehicleDto>> listVehicles() {
		return Mono.fromSupplier(() -> gameState.listVehicles().stream()
			.map(VehicleDto::from)
			.toList());
	}

	@GetMapping(path = "/vehicles/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<VehicleEvent> vehicleStream() {
		return engine.vehicleStream();
	}

	/**
	 * Wipes the in-memory game state and restores the starting balance. Phase 3: returns 204.
	 * Future phases may persist a snapshot before reset to support undo.
	 */
	@PostMapping("/reset")
	public Mono<ResponseEntity<Void>> resetGame() {
		return Mono.fromRunnable(() -> {
			gameState.reset();
			orderGenerator.reset();
			engine.milestoneTracker().reset();
		}).thenReturn(ResponseEntity.noContent().<Void>build());
	}

	// ─── Phase 6: restaurant orders ───────────────────────────────────────

	@GetMapping("/orders")
	public Mono<List<OrderDto>> listAllOrders() {
		return Mono.fromSupplier(() -> gameState.listAllOrders().stream().map(OrderDto::from).toList());
	}

	@GetMapping("/restaurants/{id}/orders")
	public Mono<List<OrderDto>> listOrdersForRestaurant(@PathVariable UUID id) {
		return Mono.fromSupplier(() -> gameState.listOrders(id).stream().map(OrderDto::from).toList());
	}

	@PostMapping("/restaurants/{id}/orders")
	public Mono<ResponseEntity<OrderDto>> enqueueOrder(
		@PathVariable UUID id,
		@RequestBody EnqueueOrderRequest request
	) {
		return Mono.fromSupplier(() -> gameState
			.enqueueOrder(id, request.recipeId(), request.quantity(), request.patienceGameMinutes())
			.map(order -> {
				engine.publishOrderEvent(new OrderEvent.Enqueued(order, gameState.getClockSnapshot().gameMinutes()));
				return ResponseEntity.status(HttpStatus.CREATED).body(OrderDto.from(order));
			})
			.orElseGet(() -> ResponseEntity.notFound().<OrderDto>build()));
	}

	@PostMapping("/restaurants/{restaurantId}/orders/{orderId}/fulfill")
	public Mono<ResponseEntity<FulfillOrderResponse>> fulfillOrder(
		@PathVariable UUID restaurantId,
		@PathVariable UUID orderId
	) {
		return Mono.fromSupplier(() -> gameState
			.fulfillOrder(restaurantId, orderId)
			.map(outcome -> {
				engine.publishOrderEvent(new OrderEvent.Fulfilled(
					orderId,
					restaurantId,
					outcome.result(),
					outcome.payout(),
					outcome.newBalance(),
					outcome.newReputation(),
					gameState.getClockSnapshot().gameMinutes()));
				// Phase-8: feed the milestone tracker. We don't yet pass perishable / cuisine
				// / route metadata through the fulfill request — that's wired in Phase 9 when
				// the walker's leg metadata reaches the backend. For now COLD_CHAIN unlocks
				// off any successful fulfilment (safe over-trigger; the milestone is meant to
				// celebrate "deliveries are working").
				engine.milestoneTracker().recordFulfillment(
					gameState.getClockSnapshot().gameMinutes(), true, "", "");
				return ResponseEntity.ok(new FulfillOrderResponse(
					outcome.result().name(),
					outcome.payout(),
					outcome.newBalance(),
					outcome.newReputation()));
			})
			// Order is no longer pending — most likely already drained by the engine after its
			// deadline elapsed, or fulfilled twice from a slow click. Treat as a benign no-op
			// rather than a 404 so the frontend doesn't show an error toast for what the
			// player thought was a successful click.
			.orElseGet(() -> {
				long currentBalance = gameState.getBalance().amount();
				double currentReputation = gameState.listBuildings().stream()
					.filter(b -> b.id().equals(restaurantId) && b instanceof Restaurant)
					.map(b -> ((Restaurant) b).reputation())
					.findFirst()
					.orElse(0.0);
				return ResponseEntity.ok(new FulfillOrderResponse(
					"EXPIRED", 0L, currentBalance, currentReputation));
			}));
	}

	/**
	 * Phase-7: mark an order as spoiled in transit. The frontend calls this when a delivery
	 * van arrives but the cargo's {@code shelfLifeMinutes} ran out en route — no payout, the
	 * "missed delivery" reputation hit applies. Lenient on unknown ids for the same reason
	 * fulfill is.
	 */
	@PostMapping("/restaurants/{restaurantId}/orders/{orderId}/spoil")
	public Mono<ResponseEntity<FulfillOrderResponse>> spoilOrder(
		@PathVariable UUID restaurantId,
		@PathVariable UUID orderId
	) {
		return Mono.fromSupplier(() -> gameState
			.spoilOrder(restaurantId, orderId)
			.map(outcome -> {
				engine.publishOrderEvent(new OrderEvent.Fulfilled(
					orderId,
					restaurantId,
					outcome.result(),
					outcome.payout(),
					outcome.newBalance(),
					outcome.newReputation(),
					gameState.getClockSnapshot().gameMinutes()));
				return ResponseEntity.ok(new FulfillOrderResponse(
					outcome.result().name(),
					outcome.payout(),
					outcome.newBalance(),
					outcome.newReputation()));
			})
			.orElseGet(() -> ResponseEntity.ok(new FulfillOrderResponse(
				"EXPIRED", 0L, gameState.getBalance().amount(), 0.0))));
	}

	/**
	 * Server-sent stream of order lifecycle events (enqueued / fulfilled / expired). Mirrors
	 * the {@code /clock/stream} pattern; the frontend's {@code RestaurantService} subscribes
	 * to it and reflects the events into its {@code _orders} signal.
	 */
	@GetMapping(path = "/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<OrderEvent> orderStream() {
		return engine.orderStream();
	}

	// ─── DTOs ───────────────────────────────────────────────────────────────

	public record BalanceResponse(long amount) {
	}

	public record BuildingDto(
		UUID id,
		String kind,
		double lat,
		double lon,
		String recipeId,
		@Nullable String outputIngredientId,
		@Nullable List<String> operations,
		@Nullable Double reputation,
		@Nullable Boolean closed,
		@Nullable String templateId,
		long cycleStartedAtGameMinutes,
		long cycleDurationGameMinutes,
		long producedUnits,
		@Nullable Boolean refrigerated,
		@Nullable Map<String, Integer> inputStockpile,
		/**
		 * Phase-12: a factory is "stalled" iff its input stockpile can't cover one full
		 * production cycle for the configured recipe. Null for farms / restaurants.
		 * Drives the {@code .building-marker.factory.stalled} grayscale rule on the
		 * frontend so the player can see at a glance which factories are starved.
		 */
		@Nullable Boolean stalled,
		/**
		 * Phase-13: lifetime fulfilled-order count for restaurants (FULFILLED + LATE).
		 * Null for farms / factories. Surfaced in the restaurant info drawer so the
		 * player has a concrete throughput signal beyond reputation.
		 */
		@Nullable Long fulfilledOrders
	) {
		/**
		 * Registry-free fallback used in test helpers and any code path that doesn't have
		 * a {@link ContentRegistry} on hand. Stalled is reported as null (unknown).
		 */
		public static BuildingDto from(Building building) {
			return from(building, null);
		}

		public static BuildingDto from(Building building, @Nullable ContentRegistry registry) {
			List<String> ops = (building instanceof Factory factory) ? factory.operations() : null;
			Double reputation = (building instanceof Restaurant restaurant) ? restaurant.reputation() : null;
			Boolean closed = (building instanceof Restaurant restaurant) ? restaurant.closed() : null;
			String templateId = (building instanceof Restaurant restaurant) ? restaurant.templateId() : null;
			Boolean refrigerated = (building instanceof Factory factory) ? factory.refrigerated() : null;
			Map<String, Integer> inputStockpile = (building instanceof Factory factory)
				? factory.inputStockpile() : null;
			Boolean stalled = null;
			if (building instanceof Factory factory && registry != null) {
				stalled = registry.findRecipe(factory.recipeId())
					.map(recipe -> !recipe.inputs().isEmpty() && !factory.hasInputsFor(recipe))
					.orElse(false);
			}
			Long fulfilledOrders = (building instanceof Restaurant restaurant)
				? restaurant.fulfilledOrders() : null;
			return new BuildingDto(
				building.id(),
				building.kind().name(),
				building.lat(),
				building.lon(),
				building.recipeId(),
				building.outputIngredientId(),
				ops,
				reputation,
				closed,
				templateId,
				building.cycleStartedAtGameMinutes(),
				building.cycleDurationGameMinutes(),
				building.producedUnits(),
				refrigerated,
				inputStockpile,
				stalled,
				fulfilledOrders);
		}
	}

	public record OrderDto(
		UUID id,
		UUID restaurantId,
		String recipeId,
		int quantity,
		long createdAtGameMinutes,
		long deadlineGameMinutes
	) {
		public static OrderDto from(Order order) {
			return new OrderDto(
				order.id(),
				order.restaurantId(),
				order.recipeId(),
				order.quantity(),
				order.createdAtGameMinutes(),
				order.deadlineGameMinutes());
		}
	}

	public record EnqueueOrderRequest(String recipeId, int quantity, long patienceGameMinutes) {
	}

	public record FulfillOrderResponse(String result, long payout, long newBalance, double newReputation) {
	}

	public record PlaceBuildingRequest(
		String kind,
		double lat,
		double lon,
		String recipeId,
		@Nullable String templateId
	) {
	}

	public record ReorderOperationsRequest(List<String> operations) {
	}


	public record PlaceBuildingResponse(
		@Nullable BuildingDto building,
		@Nullable Long balanceAmount,
		@Nullable String error,
		@Nullable Long requiredAmount
	) {
	}

	public record MilestoneDto(String id, boolean unlocked, @Nullable Long unlockedAtGameMinutes) {
	}

	public record MilestonesResponse(List<MilestoneDto> milestones, long fulfilledCount) {
	}

	/**
	 * Phase-12 vehicle wire shape. Path is serialised as {@code List<double[2]>} so the
	 * frontend can consume it as plain {@code [lat, lon]} pairs without bringing in a
	 * dedicated LatLon model.
	 */
	public record VehicleDto(
		UUID id,
		String kind,
		UUID sourceBuildingId,
		UUID destinationBuildingId,
		Map<String, Integer> cargo,
		List<double[]> path,
		long spawnedAtGameMinutes,
		long arrivesAtGameMinutes,
		double metersPerGameMinute,
		@Nullable UUID orderId,
		@Nullable Long spoilageDeadlineGameMinutes
	) {
		public static VehicleDto from(Vehicle vehicle) {
			List<double[]> path = vehicle.path().stream()
				.map(point -> new double[]{point.lat(), point.lon()})
				.toList();
			return new VehicleDto(
				vehicle.id(),
				vehicle.kind().name(),
				vehicle.sourceBuildingId(),
				vehicle.destinationBuildingId(),
				vehicle.cargo(),
				path,
				vehicle.spawnedAtGameMinutes(),
				vehicle.arrivesAtGameMinutes(),
				vehicle.metersPerGameMinute(),
				vehicle.orderId(),
				vehicle.spoilageDeadlineGameMinutes());
		}

		public static VehicleDto from(Robot robot) {
			return from((Vehicle) robot);
		}
	}
}

