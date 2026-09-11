package dev.aperture.instrument;

/** What kind of security an instrument is. Determines the Webull category used to quote it. */
public enum SecurityType {

    COMMON_STOCK("Common stock"),
    ETF("ETF"),
    ETN("ETN"),
    ADR("ADR"),
    REIT("REIT"),
    CLOSED_END_FUND("Closed-end fund"),
    INDEX("Index");

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

    /** An index can be quoted but never held or traded. */
    public boolean isTradable() {
        return this != INDEX;
    }
}
