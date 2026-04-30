package com.dimsumdetours.sim.model;

/**
 * Outcome of a delivery against an {@link Order}. Drives reputation deltas + payout multipliers
 * in {@code GameState.fulfillOrder}.
 */
public enum OrderResult {
	/** Delivered before the deadline — full payout, reputation up. */
	FULFILLED,
	/** Delivered after the deadline — discounted payout, reputation down. */
	LATE,
	/** Never delivered; the order timed out. Reputation hit, no payout. */
	EXPIRED
}

