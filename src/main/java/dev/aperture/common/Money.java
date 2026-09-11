package dev.aperture.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A cash amount in a single currency.
 *
 * <p>Held at {@link #INTERNAL_SCALE} decimal places rather than 2. Fees, per-share dividends and
 * pro-rata allocations routinely produce sub-cent values, and rounding each intermediate step to
 * cents accumulates a drift that shows up as a portfolio that does not reconcile. Rounding to
 * cents happens once, at the display boundary, via {@link #toDisplay()}.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static final int INTERNAL_SCALE = 6;
    public static final String USD = "USD";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(INTERNAL_SCALE, RoundingMode.HALF_EVEN);
    }

    public static Money usd(BigDecimal amount) {
        return new Money(amount, USD);
    }

    public static Money usd(double amount) {
        return usd(BigDecimal.valueOf(amount));
    }

    public static Money usd(long amount) {
        return usd(BigDecimal.valueOf(amount));
    }

    public static Money zero() {
        return usd(BigDecimal.ZERO);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /** Cents, for display and for JSON leaving the API. */
    public BigDecimal toDisplay() {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public String toString() {
        return toDisplay().toPlainString() + " " + currency;
    }
}
