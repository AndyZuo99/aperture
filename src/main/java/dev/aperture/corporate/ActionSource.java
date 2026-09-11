package dev.aperture.corporate;

/**
 * Where a corporate action came from.
 *
 * <p>Recorded and displayed because the three are not equally trustworthy, and a chart that has
 * been restated by a hand-entered split should say so. Silently mixing an observed vendor dividend
 * with an asserted split would make the adjusted series look more authoritative than it is.
 */
public enum ActionSource {

    /** Returned by the Webull API. Observed. */
    VENDOR("Vendor", "Reported by Webull"),

    /**
     * Aperture's built-in table of well-known historical splits.
     *
     * <p>This exists because Webull's corporate-action endpoint
     * ({@code /instrument/corp-action}) returns {@code 404 UnknownServerError} - it appears
     * retired - and its {@code EventType} dictionary only ever covered splits anyway. Without a
     * vendor split feed, the alternative to a short curated table is an unadjusted chart with a
     * 90% cliff in it.
     */
    REFERENCE("Reference", "Aperture's built-in table of known historical splits"),

    /** Entered through the API by the operator. Asserted, not observed. */
    DECLARED("Declared", "Entered manually");

    private final String label;
    private final String description;

    ActionSource(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Whether the action was observed from a data feed rather than asserted. */
    public boolean isObserved() {
        return this == VENDOR;
    }
}
