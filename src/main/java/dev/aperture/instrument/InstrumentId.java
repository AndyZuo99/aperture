package dev.aperture.instrument;

import java.util.Objects;

/**
 * A stable internal identifier for a security.
 *
 * <p>Deliberately not the ticker. Tickers are reused and reassigned - FB became META, and a
 * position keyed by "FB" would silently point at nothing after the rename. Symbols map onto an
 * instrument id; the id is what positions, orders and corporate actions reference.
 */
public record InstrumentId(String value) implements Comparable<InstrumentId> {

    public InstrumentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Instrument id cannot be blank");
        }
    }

    public static InstrumentId of(String value) {
        return new InstrumentId(value);
    }

    @Override
    public int compareTo(InstrumentId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
