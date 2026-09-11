package dev.aperture.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A share count.
 *
 * <p>Fractional rather than integral: a 3-for-2 split on an odd lot produces a fractional share,
 * and brokers including Webull support fractional trading. Scale is {@link #SCALE} so that a
 * split ratio applied and then reversed returns the original count.
 *
 * <p>Signed, so a short position is a negative quantity rather than a separate flag.
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    public static final int SCALE = 8;

    public Quantity {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    public static Quantity of(long value) {
        return new Quantity(BigDecimal.valueOf(value));
    }

    public static Quantity of(double value) {
        return new Quantity(BigDecimal.valueOf(value));
    }

    public static Quantity zero() {
        return new Quantity(BigDecimal.ZERO);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.value));
    }

    public Quantity scaledBy(BigDecimal factor) {
        return new Quantity(value.multiply(factor));
    }

    public Quantity negated() {
        return new Quantity(value.negate());
    }

    public Quantity abs() {
        return new Quantity(value.abs());
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isGreaterThan(Quantity other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Quantity other) {
        return compareTo(other) < 0;
    }

    /** Trailing zeros stripped, so 100.00000000 displays as "100". */
    public BigDecimal toDisplay() {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return toDisplay().toPlainString();
    }
}
