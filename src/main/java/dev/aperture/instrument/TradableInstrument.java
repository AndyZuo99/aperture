package dev.aperture.instrument;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One security an account can trade, normalised across the four very different vendor shapes.
 *
 * <p>The four universes return genuinely different objects - a stock has {@code marginable} and
 * {@code shortable}, a futures contract has a contract month and a tick size, an event contract
 * has a settlement question and a last trading date. Rather than forcing them into one wide
 * record where three quarters of the fields are always null, the class-specific facts live in
 * {@link #attributes()} as ordered label/value pairs that the UI renders as-is.
 *
 * <p>That keeps the shared columns honest - every universe really does have a symbol, a name and
 * a status - while letting each one show what actually matters about it.
 *
 * @param group a secondary grouping within the universe: a futures product class, an event
 *     series, or an equity sub-category. Empty when the universe is flat.
 * @param tradable whether the vendor currently reports it as open for trading
 */
public record TradableInstrument(
        String symbol,
        String name,
        TradableUniverse universe,
        String group,
        String status,
        boolean tradable,
        Map<String, String> attributes) {

    public TradableInstrument {
        Objects.requireNonNull(universe, "universe");
        symbol = symbol == null ? "" : symbol.toUpperCase();
        name = name == null || name.isBlank() ? symbol : name;
        group = group == null ? "" : group;
        status = status == null ? "" : status;
        // LinkedHashMap, not Map.copyOf: the attribute order is the display order, and
        // Map.copyOf gives an unspecified iteration order that would shuffle the columns.
        attributes = attributes == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Attributes that describe borrowing or leverage, and so mean nothing in a cash account.
     *
     * <p>A security being shortable is a property of the security; whether <em>you</em> can short
     * it is a property of the account. A cash account cannot short or buy on margin at all, so
     * rendering "Shortable: Yes" there states something true about the instrument and false about
     * what the account could do with it - the more misleading of the two.
     */
    public static final java.util.Set<String> MARGIN_ONLY_ATTRIBUTES = java.util.Set.of(
            "Marginable", "Shortable", "Easy to borrow",
            "Margin req. long", "Margin req. short");

    /**
     * The attributes worth showing for an account of this type.
     *
     * @param marginAccount whether the account can borrow; a cash account has the leverage and
     *     borrow attributes suppressed rather than shown as capabilities it does not have
     */
    public Map<String, String> attributesFor(boolean marginAccount) {
        if (marginAccount) {
            return attributes;
        }
        Map<String, String> visible = new LinkedHashMap<>();
        attributes.forEach((label, value) -> {
            if (!MARGIN_ONLY_ATTRIBUTES.contains(label)) {
                visible.put(label, value);
            }
        });
        return java.util.Collections.unmodifiableMap(visible);
    }

    /** Whether this instrument matches a free-text search over symbol, name and group. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase();
        return symbol.toLowerCase().contains(needle)
                || name.toLowerCase().contains(needle)
                || group.toLowerCase().contains(needle);
    }

    /** Builder for attribute maps that keeps insertion order and drops blanks. */
    public static final class Attributes {

        private final Map<String, String> values = new LinkedHashMap<>();

        public Attributes put(String label, String value) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                values.put(label, value.trim());
            }
            return this;
        }

        public Attributes putFlag(String label, Boolean value) {
            if (value != null) {
                values.put(label, value ? "Yes" : "No");
            }
            return this;
        }

        /**
         * Formats a vendor decimal for display, stripping the trailing zeros they pad everything
         * with - "0.5000000000" is a margin requirement of 50%, and reads as noise otherwise.
         */
        public Attributes putDecimal(String label, String value) {
            if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
                return this;
            }
            try {
                return put(label, new java.math.BigDecimal(value.trim())
                        .stripTrailingZeros().toPlainString());
            } catch (NumberFormatException e) {
                return put(label, value);
            }
        }

        public Attributes putPercent(String label, String value) {
            if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
                return this;
            }
            try {
                java.math.BigDecimal fraction = new java.math.BigDecimal(value.trim());
                return put(label, fraction.movePointRight(2).stripTrailingZeros()
                        .toPlainString() + "%");
            } catch (NumberFormatException e) {
                return put(label, value);
            }
        }

        public Map<String, String> build() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
