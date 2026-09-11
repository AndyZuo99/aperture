package dev.aperture.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.config.ApertureProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate on live order submission.
 *
 * <p>Two independent conditions, and these tests assert that each one alone is <em>not</em>
 * enough. A control that only gets tested in its firing state is a control nobody has checked can
 * refuse - and here the failure direction is real money.
 */
class LiveTradingGateTest {

    @Test
    @DisplayName("closed by default")
    void closedByDefault() {
        assertThat(webull(false, "").liveOrdersPermitted()).isFalse();
    }

    @Test
    @DisplayName("the boolean alone is not enough")
    void booleanAloneIsInsufficient() {
        // The point of the second condition: one stray environment variable, one merged config
        // file, must not be sufficient.
        assertThat(webull(true, "").liveOrdersPermitted()).isFalse();
        assertThat(webull(true, "yes").liveOrdersPermitted()).isFalse();
        assertThat(webull(true, "true").liveOrdersPermitted()).isFalse();
    }

    @Test
    @DisplayName("the confirmation phrase alone is not enough")
    void phraseAloneIsInsufficient() {
        assertThat(webull(false, ApertureProperties.Webull.REQUIRED_CONFIRMATION)
                .liveOrdersPermitted()).isFalse();
    }

    @Test
    @DisplayName("a near-miss phrase does not open the gate")
    void nearMissPhraseIsRejected() {
        assertThat(webull(true, "I accept real money orders").liveOrdersPermitted()).isFalse();
        assertThat(webull(true, "I ACCEPT REAL MONEY ORDER").liveOrdersPermitted()).isFalse();
    }

    @Test
    @DisplayName("both conditions together open the gate")
    void bothConditionsOpenIt() {
        assertThat(webull(true, ApertureProperties.Webull.REQUIRED_CONFIRMATION)
                .liveOrdersPermitted()).isTrue();
    }

    @Test
    @DisplayName("surrounding whitespace in the phrase is tolerated")
    void phraseIsTrimmed() {
        assertThat(webull(true, "  " + ApertureProperties.Webull.REQUIRED_CONFIRMATION + " ")
                .liveOrdersPermitted()).isTrue();
    }

    @Test
    @DisplayName("the block reason names the condition that is missing")
    void blockReasonIsSpecific() {
        assertThat(webull(false, "").liveOrderBlockReason()).contains("allow-live-trading");
        assertThat(webull(true, "").liveOrderBlockReason()).contains("confirmation");
        assertThat(webull(true, ApertureProperties.Webull.REQUIRED_CONFIRMATION)
                .liveOrderBlockReason()).isEmpty();
    }

    @Test
    @DisplayName("sandbox is the default environment")
    void defaultsToSandbox() {
        assertThat(webull(false, "").defaultTradingEnvironment())
                .isEqualTo(TradingEnvironment.SANDBOX);
    }

    private static ApertureProperties.Webull webull(boolean allow, String confirmation) {
        return new ApertureProperties.Webull("key", "secret", "", "", "us", true,
                "SANDBOX", allow, confirmation, Duration.ofMinutes(6));
    }
}
