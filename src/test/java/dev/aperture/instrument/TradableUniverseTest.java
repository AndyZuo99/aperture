package dev.aperture.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule that decides which securities an account can trade.
 *
 * <p>The account classes here are the real ones returned by Webull for this account holder, in
 * both environments - they are what the mapping has to get right.
 */
class TradableUniverseTest {

    @ParameterizedTest(name = "{0} trades {1}")
    @CsvSource({
            // Every account class the live account actually returns.
            "EVENTS_CASH,        EVENT",
            "FUTURES,            FUTURES",
            "CRYPTO,             CRYPTO",
            "INDIVIDUAL_CASH,    EQUITY",
            "INDIVIDUAL_MARGIN,  EQUITY",
            "TRADITIONAL_IRA,    EQUITY",
    })
    void mapsRealAccountClasses(String accountClass, TradableUniverse expected) {
        assertThat(TradableUniverse.forAccountClass(accountClass)).isEqualTo(expected);
    }

    @Test
    @DisplayName("matching is case-insensitive")
    void caseInsensitive() {
        assertThat(TradableUniverse.forAccountClass("events_cash"))
                .isEqualTo(TradableUniverse.EVENT);
        assertThat(TradableUniverse.forAccountClass("Futures"))
                .isEqualTo(TradableUniverse.FUTURES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROTH_IRA", "JOINT_MARGIN", "CORPORATE_CASH", "SOMETHING_NEW"})
    @DisplayName("an unrecognised class falls back to equities")
    void unknownClassFallsBackToEquity(String accountClass) {
        // The vendor's class strings are open-ended, so new ones will appear. Equities is both
        // the commonest case and the least confusing default: showing stocks to an exotic account
        // is cosmetic, whereas defaulting to futures would be actively misleading.
        assertThat(TradableUniverse.forAccountClass(accountClass))
                .isEqualTo(TradableUniverse.EQUITY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("a missing class falls back to equities rather than throwing")
    void missingClassFallsBack(String accountClass) {
        assertThat(TradableUniverse.forAccountClass(accountClass))
                .isEqualTo(TradableUniverse.EQUITY);
    }

    @Test
    @DisplayName("account TYPE does not decide the universe - CLASS does")
    void typeIsOrthogonalToUniverse() {
        // This is the distinction that makes the mapping work. Both of these are CASH accounts
        // by type, and they trade completely different things.
        assertThat(TradableUniverse.forAccountClass("EVENTS_CASH"))
                .isEqualTo(TradableUniverse.EVENT);
        assertThat(TradableUniverse.forAccountClass("INDIVIDUAL_CASH"))
                .isEqualTo(TradableUniverse.EQUITY);
    }

    @Test
    @DisplayName("only equities use the Level 1 equity quote feed")
    void quoteFeedApplicability() {
        assertThat(TradableUniverse.EQUITY.usesEquityQuoteFeed()).isTrue();
        assertThat(TradableUniverse.FUTURES.usesEquityQuoteFeed()).isFalse();
        assertThat(TradableUniverse.CRYPTO.usesEquityQuoteFeed()).isFalse();
        assertThat(TradableUniverse.EVENT.usesEquityQuoteFeed()).isFalse();
    }

    @Test
    @DisplayName("every universe maps to a distinct vendor category")
    void categoriesAreDistinct() {
        assertThat(java.util.Arrays.stream(TradableUniverse.values())
                .map(TradableUniverse::category)
                .distinct()
                .count())
                .isEqualTo(TradableUniverse.values().length);
    }
}
