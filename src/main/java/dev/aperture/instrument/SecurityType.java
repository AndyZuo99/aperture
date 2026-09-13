package dev.aperture.instrument;

/** What kind of security an instrument is. Determines the Webull category used to quote it. */
public enum SecurityType {

    COMMON_STOCK("Common stock"),
    ETF("ETF"),
    ETN("ETN"),
    ADR("ADR"),
    REIT("REIT"),
    CLOSED_END_FUND("Closed-end fund"),
    INDEX("Index"),

    /* Non-equity types. These decide which vendor endpoints an instrument can use at all, so
       they must be modelled rather than inferred from the symbol. */
    CRYPTO("Crypto"),
    EVENT_CONTRACT("Event contract"),
    FUTURE("Futures contract");

    private final String label;

    SecurityType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Fund-like instruments quote under Webull's US_ETF category rather than US_STOCK. */
    public boolean isFundLike() {
        return this == ETF || this == ETN || this == CLOSED_END_FUND;
    }

    /**
     * Which universe this type belongs to.
     *
     * <p>Drives which vendor endpoints apply: equity bars and snapshots are simply the wrong calls
     * for a crypto pair or an event contract, and issuing them produces confusing failures rather
     * than useful data.
     */
    public TradableUniverse universe() {
        return switch (this) {
            case CRYPTO -> TradableUniverse.CRYPTO;
            case EVENT_CONTRACT -> TradableUniverse.EVENT;
            case FUTURE -> TradableUniverse.FUTURES;
            default -> TradableUniverse.EQUITY;
        };
    }

    /** An index can be quoted but never held or traded. */
    public boolean isTradable() {
        return this != INDEX;
    }

    /** Whether this is a listed equity or fund, as opposed to crypto, futures or an event. */
    public boolean isEquityLike() {
        return universe() == TradableUniverse.EQUITY && this != INDEX;
    }
}
