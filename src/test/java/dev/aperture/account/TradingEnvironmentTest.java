package dev.aperture.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TradingEnvironmentTest {

    @Test
    @DisplayName("the two environments differ only by endpoint")
    void endpoints() {
        assertThat(TradingEnvironment.PRODUCTION.endpoint()).isEqualTo("api.webull.com");
        assertThat(TradingEnvironment.SANDBOX.endpoint()).isEqualTo("api.sandbox.webull.com");
    }

    @Test
    @DisplayName("only production is real money")
    void onlyProductionIsReal() {
        assertThat(TradingEnvironment.PRODUCTION.isReal()).isTrue();
        assertThat(TradingEnvironment.SANDBOX.isReal()).isFalse();
    }

    @Test
    @DisplayName("common aliases parse")
    void parsesAliases() {
        assertThat(TradingEnvironment.parse("live")).isEqualTo(TradingEnvironment.PRODUCTION);
        assertThat(TradingEnvironment.parse("PROD")).isEqualTo(TradingEnvironment.PRODUCTION);
        assertThat(TradingEnvironment.parse("paper")).isEqualTo(TradingEnvironment.SANDBOX);
        assertThat(TradingEnvironment.parse("  sim ")).isEqualTo(TradingEnvironment.SANDBOX);
    }

    @Test
    @DisplayName("strict parsing rejects nonsense")
    void strictParseRejectsUnknown() {
        assertThatThrownBy(() -> TradingEnvironment.parse("wherever"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lenient parsing falls back to sandbox, never production")
    void lenientParseFailsSafe() {
        // Used on inbound request parameters. The safe direction for an unrecognised value is
        // paper trading; guessing production would be the one unrecoverable mistake.
        assertThat(TradingEnvironment.parseOrSandbox("wherever"))
                .isEqualTo(TradingEnvironment.SANDBOX);
        assertThat(TradingEnvironment.parseOrSandbox(null)).isEqualTo(TradingEnvironment.SANDBOX);
        assertThat(TradingEnvironment.parseOrSandbox("")).isEqualTo(TradingEnvironment.SANDBOX);
    }
}
