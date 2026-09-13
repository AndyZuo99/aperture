package dev.aperture.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Which universe each security type belongs to.
 *
 * <p>This mapping decides which vendor endpoints an instrument may use at all. Getting it wrong is
 * not cosmetic: the equity bar endpoint asked for a crypto pair fails on every scheduled run, and
 * because the symbol is never marked complete the backfill retries it forever.
 */
class SecurityTypeUniverseTest {

    @ParameterizedTest(name = "{0} trades in {1}")
    @CsvSource({
            "COMMON_STOCK,    EQUITY",
            "ETF,             EQUITY",
            "ETN,             EQUITY",
            "ADR,             EQUITY",
            "REIT,            EQUITY",
            "CLOSED_END_FUND, EQUITY",
            "INDEX,           EQUITY",
            "CRYPTO,          CRYPTO",
            "EVENT_CONTRACT,  EVENT",
            "FUTURE,          FUTURES",
    })
    void mapsEachTypeToItsUniverse(SecurityType type, TradableUniverse expected) {
        assertThat(type.universe()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an index is equity-universe but never equity-LIKE, because it cannot be held")
    void indexIsNotEquityLike() {
        // It quotes through the equity feed, so it belongs to that universe - but it is not
        // tradable, and the backfill and candidate scan must not treat it as a holding.
        assertThat(SecurityType.INDEX.universe()).isEqualTo(TradableUniverse.EQUITY);
        assertThat(SecurityType.INDEX.isEquityLike()).isFalse();
        assertThat(SecurityType.INDEX.isTradable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SecurityType.class,
            names = {"CRYPTO", "EVENT_CONTRACT", "FUTURE"})
    @DisplayName("non-equity types are never equity-like")
    void nonEquityTypesAreNotEquityLike(SecurityType type) {
        // The guard that keeps getBatchBars from being called for a crypto pair.
        assertThat(type.isEquityLike()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SecurityType.class,
            names = {"COMMON_STOCK", "ETF", "ETN", "ADR", "REIT", "CLOSED_END_FUND"})
    @DisplayName("listed equities and funds are equity-like and tradable")
    void listedTypesAreEquityLike(SecurityType type) {
        assertThat(type.isEquityLike()).isTrue();
        assertThat(type.isTradable()).isTrue();
    }

    @Test
    @DisplayName("fund-like types quote under the vendor's ETF category, not US_STOCK")
    void fundLikeTypes() {
        assertThat(SecurityType.ETF.isFundLike()).isTrue();
        assertThat(SecurityType.ETN.isFundLike()).isTrue();
        assertThat(SecurityType.CLOSED_END_FUND.isFundLike()).isTrue();
        assertThat(SecurityType.COMMON_STOCK.isFundLike()).isFalse();
        // Crypto is not a fund however you squint at it - it simply is not an equity category.
        assertThat(SecurityType.CRYPTO.isFundLike()).isFalse();
    }

    @Test
    @DisplayName("every universe is reachable from some security type")
    void everyUniverseHasAType() {
        // Otherwise a universe could be configured that no instrument can ever belong to.
        for (TradableUniverse universe : TradableUniverse.values()) {
            assertThat(java.util.Arrays.stream(SecurityType.values())
                    .anyMatch(type -> type.universe() == universe))
                    .describedAs("no SecurityType maps to %s", universe)
                    .isTrue();
        }
    }
}
