package dev.aperture.marketdata;

/**
 * Where a quote came from, and therefore how much it can be trusted.
 *
 * <p>Carried on every quote and surfaced in the UI. These are different claims about reality and
 * the interface must never let one read as another - a simulated print that looks like a live one
 * is worse than no print at all, because it invites a decision.
 */
public enum QuoteProvenance {

    /** Live Level 1 from the Webull MQTT stream. Real bid, ask and last. */
    LIVE_STREAM("Live", "Streaming Level 1 from Webull", true),

    /** Live, but pulled by polling the REST endpoint rather than pushed. Slightly staler. */
    LIVE_REST("Live (polled)", "Webull REST snapshot", true),

    /**
     * Generated locally by the simulator. Not market data.
     *
     * <p>Exists so the application is runnable and demonstrable without credentials or an
     * entitlement, which is the difference between a reviewer seeing it work and seeing a stack
     * trace.
     */
    SIMULATED("Simulated", "Generated locally - not market data", false);

    private final String label;
    private final String description;
    private final boolean live;

    QuoteProvenance(String label, String description, boolean live) {
        this.label = label;
        this.description = description;
        this.live = live;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Whether this quote reflects an actual market. */
    public boolean isLive() {
        return live;
    }
}
