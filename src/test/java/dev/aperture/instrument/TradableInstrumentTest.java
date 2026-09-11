package dev.aperture.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TradableInstrumentTest {

    @Test
    @DisplayName("a cash account does not see borrow or leverage attributes")
    void cashAccountHidesMarginAttributes() {
        // Shortable is a property of the security; being able to short is a property of the
        // account. A cash account cannot, so advertising it would be misleading.
        TradableInstrument stock = equity();

        assertThat(stock.attributesFor(false))
                .doesNotContainKeys("Marginable", "Shortable", "Easy to borrow",
                        "Margin req. long", "Margin req. short")
                .containsKeys("Exchange", "Fractional");
    }

    @Test
    @DisplayName("a margin account sees everything")
    void marginAccountSeesAllAttributes() {
        assertThat(equity().attributesFor(true))
                .containsKeys("Exchange", "Marginable", "Shortable", "Margin req. long");
    }

    @Test
    @DisplayName("attribute order is preserved, because it is the column order")
    void attributeOrderIsStable() {
        Map<String, String> attributes = new TradableInstrument.Attributes()
                .put("First", "1").put("Second", "2").put("Third", "3").build();

        assertThat(attributes.keySet()).containsExactly("First", "Second", "Third");
    }

    @Test
    @DisplayName("blank and literal-null vendor values are dropped, not rendered")
    void blankValuesAreDropped() {
        // The vendor returns the string "null" for absent fields rather than omitting them.
        Map<String, String> attributes = new TradableInstrument.Attributes()
                .put("Kept", "value")
                .put("Empty", "")
                .put("Null", null)
                .put("LiteralNull", "null")
                .build();

        assertThat(attributes).containsOnlyKeys("Kept");
    }

    @Test
    @DisplayName("vendor decimals are stripped of their padding zeros")
    void decimalsAreTidied() {
        Map<String, String> attributes = new TradableInstrument.Attributes()
                .putDecimal("Lot size", "1.0000000000")
                .putDecimal("Min qty", "0.0100000000")
                .build();

        assertThat(attributes).containsEntry("Lot size", "1").containsEntry("Min qty", "0.01");
    }

    @Test
    @DisplayName("margin requirements render as percentages")
    void marginRequirementsAsPercent() {
        // "0.5000000000" is a 50% requirement and reads as noise otherwise.
        assertThat(new TradableInstrument.Attributes()
                .putPercent("Margin req. long", "0.5000000000").build())
                .containsEntry("Margin req. long", "50%");
    }

    @Test
    @DisplayName("search matches symbol, name and group, case-insensitively")
    void searchMatching() {
        TradableInstrument stock = equity();

        assertThat(stock.matches("aapl")).isTrue();
        assertThat(stock.matches("APPLE")).isTrue();
        assertThat(stock.matches("common_stock")).isTrue();
        assertThat(stock.matches("tesla")).isFalse();
    }

    @Test
    @DisplayName("a blank search matches everything")
    void blankSearchMatchesAll() {
        assertThat(equity().matches("")).isTrue();
        assertThat(equity().matches(null)).isTrue();
        assertThat(equity().matches("   ")).isTrue();
    }

    @Test
    @DisplayName("a missing name falls back to the symbol")
    void nameFallsBackToSymbol() {
        // Webull returns no display name for crypto pairs or many futures contracts.
        TradableInstrument crypto = new TradableInstrument(
                "btcusd", null, TradableUniverse.CRYPTO, "Spot", "OC", true, Map.of());

        assertThat(crypto.symbol()).isEqualTo("BTCUSD");
        assertThat(crypto.name()).isEqualTo("BTCUSD");
    }

    private static TradableInstrument equity() {
        return new TradableInstrument("AAPL", "APPLE INC", TradableUniverse.EQUITY,
                "COMMON_STOCK", "OC", true,
                new TradableInstrument.Attributes()
                        .put("Exchange", "NSQ")
                        .putFlag("Marginable", true)
                        .putFlag("Shortable", true)
                        .putFlag("Easy to borrow", true)
                        .putFlag("Fractional", true)
                        .putPercent("Margin req. long", "0.5000000000")
                        .putPercent("Margin req. short", "0.5000000000")
                        .build());
    }
}
