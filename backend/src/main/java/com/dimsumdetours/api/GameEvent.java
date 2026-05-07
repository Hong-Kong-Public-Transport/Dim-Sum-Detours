package com.dimsumdetours.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Phase-19: catch-all server → client events stream. Carries every state
 * change that isn't part of the order/vehicle/milestone lifecycle (which
 * have their own dedicated SSE channels).
 *
 * <p>Lives in the {@code api} package because the
 * {@link BalanceChanged}/{@link BuildingStateChanged} variants ship wire
 * DTOs as their payload — this is intrinsically API-layer surface, not
 * pure simulation state.
 *
 * <p>Each event is self-contained: it carries its own {@code gameMinutes}
 * timestamp + {@code serverWallClockMs} + {@code worldEpoch}. A missed,
 * duplicated, or reordered message reconciles by absolute time + epoch
 * rather than by relative ordering. See {@code docs/NETWORKING.md}.
 *
 * <p>Sealed so the engine + frontend handle every variant exhaustively —
 * adding a new event type is a compile error until every {@code switch} is
 * updated.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
	property = "type", visible = true)
@JsonSubTypes({
	@JsonSubTypes.Type(value = GameEvent.BalanceChanged.class, name = "BALANCE_CHANGED"),
	@JsonSubTypes.Type(value = GameEvent.BuildingStateChanged.class, name = "BUILDING_STATE_CHANGED"),
	@JsonSubTypes.Type(value = GameEvent.RestaurantClosed.class, name = "RESTAURANT_CLOSED"),
	@JsonSubTypes.Type(value = GameEvent.WorldReset.class, name = "WORLD_RESET"),
})
public sealed interface GameEvent {

	String type();

	long gameMinutes();

	long serverWallClockMs();

	long worldEpoch();

	/** Wallet mutation. {@code reason} is a coarse machine-readable tag for
	 * the future toast/audit-log layer; {@code delta} is signed
	 * (negative = debit). The new balance is the authoritative wallet
	 * value after the mutation — replace the local cache with it. */
	record BalanceChanged(
		long newBalance,
		long delta,
		String reason,
		long gameMinutes,
		long serverWallClockMs,
		long worldEpoch
	) implements GameEvent {
		@Override public String type() { return "BALANCE_CHANGED"; }
	}

	/** Building state mutation: cargo arrival → stockpile change, factory
	 * recipe / operation reorder → cycle re-anchor, factory stall/unstall,
	 * restaurant fulfilled counter, refrigeration upgrade. The full
	 * updated DTO is shipped so the frontend never has to merge partial
	 * fields. */
	record BuildingStateChanged(
		GameController.BuildingDto building,
		long gameMinutes,
		long serverWallClockMs,
		long worldEpoch
	) implements GameEvent {
		@Override public String type() { return "BUILDING_STATE_CHANGED"; }
	}

	/** A restaurant's reputation just crossed below
	 * {@code RESTAURANT_CLOSE_REPUTATION_THRESHOLD}. The transition is
	 * one-way today (no Reopened event). */
	record RestaurantClosed(
		UUID restaurantId,
		double reputation,
		long gameMinutes,
		long serverWallClockMs,
		long worldEpoch
	) implements GameEvent {
		@Override public String type() { return "RESTAURANT_CLOSED"; }
	}

	/** World was reset. Clients drop every cache and re-fetch
	 * {@code /api/game/snapshot}. */
	record WorldReset(
		long gameMinutes,
		long serverWallClockMs,
		long worldEpoch,
		@Nullable String reason
	) implements GameEvent {
		@Override public String type() { return "WORLD_RESET"; }
	}
}

