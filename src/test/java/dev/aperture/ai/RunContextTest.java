package dev.aperture.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which account a recommendation run's tools resolve against.
 *
 * <p>A run is told its environment and account in the opening brief, as prose. The tools that take
 * an {@code accountId} defaulted to "the first account in the environment" whenever the model did
 * not repeat it back - so a run briefed on an Individual Margin account resolved its tradable
 * universe against the Events account and said so in its own output, having proposed equities for
 * an account whose universe it had never actually looked at.
 */
class RunContextTest {

    private static final String RUN_ACCOUNT = "R7IGRPEHSQLL30L4JQ6R26IBSB";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("an omitted argument resolves to the run's own account")
    void omittedArgumentUsesTheRunsAccount(String argument) {
        // The whole failure in one line: blank used to mean "first account in the environment".
        assertThat(MarketAnalyst.orRunContext(argument, RUN_ACCOUNT)).isEqualTo(RUN_ACCOUNT);
    }

    @Test
    @DisplayName("an account the model names explicitly still wins")
    void explicitArgumentWins() {
        // Asking about a different account is legitimate - "what could my crypto account trade" -
        // so the run's account is a default, not an override.
        assertThat(MarketAnalyst.orRunContext("X819410937089712128", RUN_ACCOUNT))
                .isEqualTo("X819410937089712128");
    }

    @Test
    @DisplayName("with no run account the argument is passed through untouched")
    void noRunAccountChangesNothing() {
        // The Ask path has no account context at all; it must behave exactly as it did before.
        assertThat(MarketAnalyst.orRunContext("", MarketAnalyst.RunContext.NONE.accountId()))
                .isEmpty();
        assertThat(MarketAnalyst.orRunContext("PRODUCTION",
                MarketAnalyst.RunContext.NONE.environment())).isEqualTo("PRODUCTION");
    }
}
