package dev.aperture.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aperture.account.AccountBalance;
import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.common.Money;
import dev.aperture.config.ApertureProperties;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much capital the recommender believes it has.
 *
 * <p>Drawn from a real account: a production margin account here reports <em>no</em> buying power
 * and cash of -2,358, because it carries a margin debit. A plain "buying power, else cash"
 * fallback hands that straight to the model as a negative budget - so every case below checks the
 * value is usable, not merely present.
 */
class RecommendationBudgetTest {

    private AccountService accounts;
    private MarketAnalyst analyst;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        analyst = new MarketAnalyst(
                mock(AnalystToolkit.class), mock(TradePlanner.class), accounts,
                mock(MarketDataService.class), properties(), mock(MarketClock.class), null);
    }

    @Test
    @DisplayName("an explicit capital figure wins")
    void explicitCapitalWins() {
        balance(Money.usd(999), Money.usd(999));

        assertThat(analyst.resolveBudget(Optional.of(account()), BigDecimal.valueOf(25_000)))
                .isEqualTo(Money.usd(BigDecimal.valueOf(25_000)));
    }

    @Test
    @DisplayName("buying power is used when it is positive")
    void positiveBuyingPowerIsUsed() {
        balance(Money.usd(5_000), Money.usd(100));

        assertThat(analyst.resolveBudget(Optional.of(account()), null))
                .isEqualTo(Money.usd(5_000));
    }

    @Test
    @DisplayName("a margin debit does NOT become a negative budget")
    void negativeCashIsNotABudget() {
        // The real shape: no buying power quoted, cash negative from an existing margin loan.
        balance(null, Money.usd(new BigDecimal("-2358.65")));

        Money budget = analyst.resolveBudget(Optional.of(account()), null);

        assertThat(budget.isNegative()).isFalse();
        assertThat(budget).isEqualTo(Money.zero());
    }

    @Test
    @DisplayName("zero buying power falls through rather than being taken literally")
    void zeroBuyingPowerFallsThrough() {
        balance(Money.zero(), Money.usd(4_000));

        assertThat(analyst.resolveBudget(Optional.of(account()), null))
                .isEqualTo(Money.usd(4_000));
    }

    @Test
    @DisplayName("with nothing usable the budget is zero, not an invented round number")
    void unknownBudgetIsZeroNotAGuess() {
        // Guessing would put a fabricated constraint in front of the model and size real orders
        // against it. Zero makes the brief state that the figure is unknown.
        balance(null, null);

        assertThat(analyst.resolveBudget(Optional.of(account()), null)).isEqualTo(Money.zero());
        assertThat(analyst.resolveBudget(Optional.empty(), null)).isEqualTo(Money.zero());
    }

    @Test
    @DisplayName("a non-positive explicit capital is ignored rather than honoured")
    void nonPositiveExplicitCapitalIsIgnored() {
        balance(Money.usd(7_500), Money.usd(7_500));

        assertThat(analyst.resolveBudget(Optional.of(account()), BigDecimal.ZERO))
                .isEqualTo(Money.usd(7_500));
        assertThat(analyst.resolveBudget(Optional.of(account()), BigDecimal.valueOf(-100)))
                .isEqualTo(Money.usd(7_500));
    }

    private void balance(Money buyingPower, Money cash) {
        when(accounts.balance(any())).thenReturn(new AccountBalance(
                "acct", TradingEnvironment.PRODUCTION, "USD",
                Optional.empty(), Optional.ofNullable(cash), Optional.empty(),
                Optional.ofNullable(buyingPower), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Instant.parse("2026-09-11T20:00:00Z")));
    }

    private static BrokerAccount account() {
        return new BrokerAccount("acct", "12345678", "MARGIN", "INDIVIDUAL_MARGIN",
                "Individual Margin", TradingEnvironment.PRODUCTION);
    }

    private static ApertureProperties properties() {
        return new ApertureProperties(
                new ApertureProperties.Webull("k", "s", "", "", "us", true, "SANDBOX",
                        false, "", Duration.ofMinutes(6)),
                new ApertureProperties.MarketData(List.of(), List.of(), Duration.ofSeconds(2),
                        Duration.ofSeconds(15), 250, false, Duration.ofMinutes(5)),
                new ApertureProperties.Analyst(false, "m", "", 8, 16000));
    }
}
