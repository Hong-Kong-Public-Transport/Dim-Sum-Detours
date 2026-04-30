package com.dimsumdetours.sim.model;

/**
 * In-game money. Stored as a non-negative {@code long} of "smallest currency units" — Phase 3
 * treats one unit as one whole dollar; a future phase can move to true cents without changing
 * the wire format (the field name is left agnostic via {@link #amount()}).
 *
 * <p>Framework-agnostic. Immutable.
 */
public record Money(long amount) {

	public static final Money ZERO = new Money(0);

	public Money {
		if (amount < 0) {
			throw new IllegalArgumentException("Money must be non-negative: " + amount);
		}
	}

	public static Money of(long amount) {
		return new Money(amount);
	}

	public Money plus(Money other) {
		return new Money(this.amount + other.amount);
	}

	/**
	 * @throws IllegalArgumentException if {@code other > this} (no overdrafts in Phase 3).
	 */
	public Money minus(Money other) {
		if (other.amount > this.amount) {
			throw new IllegalArgumentException(
				"Cannot subtract " + other.amount + " from " + this.amount + " (would go negative)");
		}
		return new Money(this.amount - other.amount);
	}

	public boolean isAtLeast(Money other) {
		return this.amount >= other.amount;
	}
}
