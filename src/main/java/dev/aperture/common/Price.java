package dev.aperture.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A per-share price.
 *
 * <p>Distinct from {@link Money} because the two are not interchangeable: a price times a share
 * count is money, but adding a price to a cash balance is a bug. Keeping them as separate types
 * makes that bug a compile error.
 *
 * <p>Scale is {@link #SCALE} rather than 2 because split adjustment divides by ratios like 3 and
 * 7, and a price rounded to cents before adjustment cannot be adjusted back accurately.
 */
public record Price(BigDecimal value) implements Comparable<Price> {

    public static final int SCALE = 6;

    public Price {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Price cannot be negative: " + value);
        }
        value = value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public static Price of(BigDecimal value) {
        return new Price(value);
    }

    public static Price of(double value) {
        return new Price(BigDecimal.valueOf(value));
    }

    public static Price zero() {
        return new Price(BigDecimal.ZERO);
    }

    /** Price times a share count, which is money. */
    public Money times(Quantity quantity) {
        return Money.usd(value.multiply(quantity.value()));
    }

    public Price scaledBy(BigDecimal factor) {
        return new Price(value.multiply(factor));
    }

    public Price plus(Price other) {
        return new Price(value.add(other.value));
    }

    public Price minus(Price other) {
        BigDecimal difference = value.subtract(other.value);
        return new Price(difference.signum() < 0 ? BigDecimal.ZERO : difference);
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isGreaterThan(Price other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Price other) {
        return compareTo(other) < 0;
    }

    /** The conventional display precision for US equities. */
    public BigDecimal toDisplay() {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    @Override
    public int compareTo(Price other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return toDisplay().toPlainString();
    }
}
