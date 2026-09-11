package dev.aperture.instrument;

import com.webull.openapi.core.common.dict.Category;
import java.util.Locale;

/**
 * The set of securities an account is actually able to trade.
 *
 * <p>A Webull account is not a general-purpose brokerage account: an Events account trades event
 * contracts and <em>only</em> event contracts, a Futures account trades futures, a Crypto account
 * trades coins. Showing a stock watchlist to a futures account is showing instruments that
 * account cannot buy.
 *
 * <p>The mapping is driven by the vendor's {@code accountClass}, which is the field that actually
 * determines it - not {@code accountType}, which only distinguishes CASH from MARGIN and is
 * orthogonal. A CASH account can be an equities account or an events account or a crypto account.
 */
public enum TradableUniverse {

    /** Listed equities and ETFs. The default for ordinary cash and margin accounts. */
    EQUITY("Stocks & ETFs", Category.US_STOCK,
            "Listed equities and ETFs"),

    /** Dated futures contracts. */
    FUTURES("Futures", Category.US_FUTURES,
            "Dated futures contracts by product class"),

    /** Spot crypto pairs. */
    CRYPTO("Crypto", Category.US_CRYPTO,
            "Spot crypto pairs"),

    /**
     * Binary event contracts - "will X happen by Y" - that settle at 0 or 1.
     *
     * <p>Organised as series (Fed Meeting, Jobs numbers) containing individual markets, rather
     * than as a flat symbol list, because a bare contract symbol like
     * {@code KXFEDDECISION-26SEP-H25} means nothing without its series.
     */
    EVENT("Event contracts", Category.US_EVENT,
            "Binary event contracts, grouped by series");

    private final String label;
    private final Category category;
    private final String description;

    TradableUniverse(String label, Category category, String description) {
        this.label = label;
        this.category = category;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public String description() {
        return description;
    }

    /**
     * Which universe an account class trades.
     *
     * <p>Matches on substrings rather than an exact set because the vendor's class strings are
     * open-ended - {@code EVENTS_CASH}, {@code INDIVIDUAL_MARGIN}, {@code TRADITIONAL_IRA} today,
     * with more plausible tomorrow. An unrecognised class falls back to {@link #EQUITY}, which is
     * both the commonest case and the safest: showing stocks to an exotic account is a cosmetic
     * error, whereas defaulting to futures would be a confusing one.
     */
    public static TradableUniverse forAccountClass(String accountClass) {
        if (accountClass == null || accountClass.isBlank()) {
            return EQUITY;
        }
        String normalised = accountClass.toUpperCase(Locale.ROOT);
        if (normalised.contains("EVENT")) {
            return EVENT;
        }
        if (normalised.contains("FUTURE")) {
            return FUTURES;
        }
        if (normalised.contains("CRYPTO")) {
            return CRYPTO;
        }
        return EQUITY;
    }

    /** Whether this universe is quoted through the ordinary Level 1 equity feed. */
    public boolean usesEquityQuoteFeed() {
        return this == EQUITY;
    }
}
