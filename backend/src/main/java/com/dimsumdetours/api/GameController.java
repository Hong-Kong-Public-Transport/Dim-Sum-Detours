package com.dimsumdetours.api;

import com.dimsumdetours.sim.model.Building;
import com.dimsumdetours.sim.model.BuildingKind;
import com.dimsumdetours.sim.model.Factory;
import com.dimsumdetours.sim.model.Money;
import com.dimsumdetours.sim.model.PlacementError;
import com.dimsumdetours.sim.model.PlacementResult;
import com.dimsumdetours.sim.state.GameState;
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
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
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

	@GetMapping("/balance")
	public Mono<BalanceResponse> getBalance() {
		return Mono.fromSupplier(() -> new BalanceResponse(gameState.getBalance().amount()));
	}

	@GetMapping("/buildings")
	public Mono<List<BuildingDto>> listBuildings() {
		return Mono.fromSupplier(() -> gameState.listBuildings().stream()
			.map(BuildingDto::from)
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

			PlacementResult result = gameState.placeBuilding(kind, request.lat(), request.lon(), request.recipeId());
			if (result instanceof PlacementResult.Success success) {
				BuildingDto dto = BuildingDto.from(success.building());
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
					.map(factory -> ResponseEntity.ok(BuildingDto.from(factory)))
					.orElseGet(() -> ResponseEntity.notFound().build());
			} catch (IllegalArgumentException ex) {
				return ResponseEntity.badRequest().<BuildingDto>build();
			}
		});
	}

	/**
	 * Wipes the in-memory game state and restores the starting balance. Phase 3: returns 204.
	 * Future phases may persist a snapshot before reset to support undo.
	 */
	@PostMapping("/reset")
	public Mono<ResponseEntity<Void>> resetGame() {
		return Mono.fromRunnable(gameState::reset).thenReturn(ResponseEntity.noContent().<Void>build());
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
		@Nullable List<String> operations
	) {
		public static BuildingDto from(Building building) {
			List<String> ops = (building instanceof Factory factory) ? factory.operations() : null;
			return new BuildingDto(
				building.id(),
				building.kind().name(),
				building.lat(),
				building.lon(),
				building.recipeId(),
				building.outputIngredientId(),
				ops);
		}
	}

	public record PlaceBuildingRequest(
		String kind,
		double lat,
		double lon,
		String recipeId
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
}

