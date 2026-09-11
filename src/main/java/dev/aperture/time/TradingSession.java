package dev.aperture.time;

/**
 * Which part of the trading day a timestamp falls in.
 *
 * <p>This matters for quote interpretation, not just display: Webull's snapshot feed returns
 * extended-hours prints, and a "last price" from an 8pm post-market print against a 4pm close is
 * a different claim than a regular-hours last. The UI labels it so the two are never confused.
 */
public enum TradingSession {

    /** 04:00-09:30 ET. */
    PRE_MARKET("Pre-market", true),

    /** 09:30-16:00 ET. The only session with full liquidity and a meaningful closing print. */
    REGULAR("Regular", true),

    /** 16:00-20:00 ET. */
    POST_MARKET("Post-market", true),

    /** Outside 04:00-20:00 ET, a weekend, or an exchange holiday. */
    CLOSED("Closed", false);

    private final String label;
    private final boolean quotesFlow;

    TradingSession(String label, boolean quotesFlow) {
        this.label = label;
        this.quotesFlow = quotesFlow;
    }

    public String label() {
        return label;
    }

    /** Whether live quotes are expected to arrive during this session. */
    public boolean quotesFlow() {
        return quotesFlow;
    }

    public boolean isExtendedHours() {
        return this == PRE_MARKET || this == POST_MARKET;
    }

    public boolean isRegular() {
        return this == REGULAR;
    }
}
