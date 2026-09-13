package dev.aperture.instrument;

import java.util.Locale;
import java.util.Optional;

/**
 * Which side of a binary event contract is being bought.
 *
 * <p>An event contract is not one instrument with one price - it is a pair. "Will the Federal
 * Reserve hike rates by more than 25bps in September?" quotes <strong>YES at 0.02 and NO at
 * 0.99</strong> simultaneously, and the two settle in opposite directions. Buying the wrong side
 * is not a rounding error; it is the opposite trade at fifty times the price.
 *
 * <p>So the outcome travels with the recommendation rather than being assumed at submission time.
 * The vendor requires it on every event order and rejects the order outright without one:
 * {@code 417 OPENAPI_PARAM_ERR: invalid event_outcome, value: null}.
 */
public enum EventOutcome {

    /** The contract settles at 1 if the stated condition happens. */
    YES("Yes"),

    /** The complement: settles at 1 if the condition does not happen. */
    NO("No");

    private final String label;

    EventOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The exact wire value. The vendor wants upper case. */
    public String wireValue() {
        return name();
    }

    /**
     * Parses an outcome, returning empty rather than guessing.
     *
     * <p>Deliberately not defaulting to {@link #YES} here: an unreadable outcome on an event order
     * should surface as a refusal, not quietly become a position on whichever side happened to be
     * the default.
     */
    public static Optional<EventOutcome> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "YES", "Y", "TRUE" -> Optional.of(YES);
            case "NO", "N", "FALSE" -> Optional.of(NO);
            default -> Optional.empty();
        };
    }
}
