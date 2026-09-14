package dev.aperture.trading;

import java.util.Locale;

/**
 * What state an order is in, grouped into the four outcomes a trader acts on.
 *
 * <p>The vendor's own status strings are open-ended and not documented exhaustively -
 * {@code SUBMITTED}, {@code FILLED}, {@code CANCELLED} and {@code PARTIAL_FILLED} are the ones
 * observed live, and others exist. Mapping an unrecognised value to {@link #UNKNOWN} rather than
 * guessing keeps a new vendor status from being silently counted as a fill.
 */
public enum OrderStatus {

    /** Live at the venue: it can still execute, and it still consumes buying power. */
    WORKING("Working", true, false),

    /** Some quantity executed, the rest still live. */
    PARTIALLY_FILLED("Partially filled", true, false),

    FILLED("Filled", false, true),
    CANCELLED("Cancelled", false, false),
    REJECTED("Rejected", false, false),

    /** A status this build does not recognise. Shown verbatim rather than reinterpreted. */
    UNKNOWN("Unknown", false, false);

    private final String label;
    private final boolean open;
    private final boolean filled;

    OrderStatus(String label, boolean open, boolean filled) {
        this.label = label;
        this.open = open;
        this.filled = filled;
    }

    public String label() {
        return label;
    }

    /** Whether the order can still execute. A resting GTC exit is the important case. */
    public boolean isOpen() {
        return open;
    }

    /** Whether the order executed in full. Partial fills are deliberately not counted here. */
    public boolean isFilled() {
        return filled;
    }

    public static OrderStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "FILLED" -> FILLED;
            case "PARTIAL_FILLED", "PARTIALLY_FILLED", "PARTIAL" -> PARTIALLY_FILLED;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            case "REJECTED", "FAILED" -> REJECTED;
            case "SUBMITTED", "WORKING", "PENDING", "QUEUED", "PENDING_SUBMIT", "PENDING_CANCEL" ->
                    WORKING;
            default -> UNKNOWN;
        };
    }
}
