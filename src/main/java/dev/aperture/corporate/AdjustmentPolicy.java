package dev.aperture.corporate;

/**
 * How much of a security's history to restate.
 *
 * <p>These produce genuinely different series and answer different questions, so the choice is
 * explicit at every call site rather than a default buried in the engine. A chart and a
 * performance figure can legitimately disagree about which one they want.
 */
public enum AdjustmentPolicy {

    /**
     * Raw vendor prints. What actually traded on the day.
     *
     * <p>Correct for "what was the closing price on 7 June 2024" and wrong for almost any
     * question spanning an ex-date.
     */
    NONE("Unadjusted", "Raw prices as traded"),

    /**
     * Splits and stock dividends only. The market convention for a price chart.
     *
     * <p>Removes the mechanical share-count discontinuity while leaving genuine cash returns
     * out of the price series.
     */
    SPLITS_ONLY("Split-adjusted", "Restated for splits and stock dividends"),

    /**
     * Splits and cash dividends - a total-return series.
     *
     * <p>The right basis for comparing performance, because it credits the cash a holder actually
     * received. Without it a high-yield name looks like a permanent slow decline.
     */
    TOTAL_RETURN("Total return", "Restated for splits and cash dividends");

    private final String label;
    private final String description;

    AdjustmentPolicy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean includesSplits() {
        return this != NONE;
    }

    public boolean includesCashDividends() {
        return this == TOTAL_RETURN;
    }

    /** Whether a given action contributes to this policy's price series. */
    public boolean applies(CorporateAction action) {
        return switch (action) {
            case CorporateAction.Split ignored -> includesSplits();
            case CorporateAction.StockDividend ignored -> includesSplits();
            case CorporateAction.CashDividend ignored -> includesCashDividends();
            case CorporateAction.SymbolChange ignored -> false;
        };
    }
}
