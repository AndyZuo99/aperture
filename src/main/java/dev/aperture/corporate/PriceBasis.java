package dev.aperture.corporate;

/**
 * What a price series has <em>already</em> been restated for.
 *
 * <p>This type exists because of a trap that is invisible until the numbers are checked against
 * reality: <strong>Webull's daily bars arrive already split-adjusted.</strong> NVDA on
 * 2023-07-05 comes back as 42.19, not the ~421.90 it actually traded at before the June 2024
 * 10-for-1 split. Applying Aperture's own split adjustment on top of that would divide by ten a
 * second time, producing a series that is wrong by an order of magnitude - and wrong <em>quietly</em>,
 * because a smooth chart of 4.2 looks no more alarming than a smooth chart of 42.
 *
 * <p>So a series is never just "adjusted" or "not". It carries the basis it is on, and
 * {@link PriceAdjuster} applies only the difference between that basis and the one asked for.
 * Without this the adjustment engine is not merely useless on vendor data - it is actively
 * destructive.
 */
public enum PriceBasis {

    /** As traded, no restatement. What a simulated or hand-entered series is on. */
    RAW("Raw", "Prices as traded, with no restatement"),

    /**
     * Splits and stock dividends already applied by the source.
     *
     * <p>The market convention for a historical bar feed, and what Webull returns.
     */
    SPLIT_ADJUSTED("Split-adjusted", "Restated for splits by the data provider"),

    /** Splits and cash dividends already applied - a total-return series. */
    TOTAL_RETURN("Total return", "Restated for splits and cash dividends");

    private final String label;
    private final String description;

    PriceBasis(String label, String description) {
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
        return this != RAW;
    }

    public boolean includesCashDividends() {
        return this == TOTAL_RETURN;
    }

    /** The basis a given policy asks for. */
    public static PriceBasis forPolicy(AdjustmentPolicy policy) {
        return switch (policy) {
            case NONE -> RAW;
            case SPLITS_ONLY -> SPLIT_ADJUSTED;
            case TOTAL_RETURN -> TOTAL_RETURN;
        };
    }

    /**
     * Whether moving from this basis to {@code target} would require <em>removing</em> an
     * adjustment the source already applied.
     *
     * <p>Un-adjusting is possible in principle - multiply by the reciprocal - but only if every
     * action the provider used is known. Aperture's split table is a short curated list, so a
     * split the provider knew about and Aperture does not would produce plausible-looking prices
     * that are silently wrong. Series are therefore never un-adjusted; the caller is told the
     * basis it actually got.
     */
    public boolean wouldRequireUnadjusting(PriceBasis target) {
        return (includesSplits() && !target.includesSplits())
                || (includesCashDividends() && !target.includesCashDividends());
    }
}
