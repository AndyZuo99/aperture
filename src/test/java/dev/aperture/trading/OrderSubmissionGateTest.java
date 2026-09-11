package dev.aperture.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.ai.OrderLeg;
import dev.aperture.ai.TradeEconomics;
import dev.aperture.ai.TradeRecommendation;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.config.ApertureProperties;
import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What stands between a click and a real order.
 *
 * <p>Every case here asserts that submission is <strong>refused</strong>, which is the point: a
 * gate is only worth having if it has been shown to shut. The failure direction is an order at a
 * live venue, so each condition is tested in isolation rather than trusting that the combination
 * works.
 */
class OrderSubmissionGateTest {

    private WebullClientHolder holder;
    private AccountService accounts;
    private MarketClock clock;

    @BeforeEach
    void setUp() {
        holder = mock(WebullClientHolder.class);
        accounts = mock(AccountService.class);
        clock = mock(MarketClock.class);
        when(clock.isRegularHours()).thenReturn(true);
    }

    @Test
    @DisplayName("production is refused while the live gate is shut")
    void productionRefusedWhenGateClosed() {
        OrderSubmissionService service = serviceWith(gate(false, ""));
        account(TradingEnvironment.PRODUCTION, "INDIVIDUAL_MARGIN");

        SubmissionResult result = service.submit(
                TradingEnvironment.PRODUCTION, "acct", viablePlan());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusedReason()).contains("allow-live-trading");
        // The decisive assertion: it never asked for a trading client, so nothing could have been
        // sent even if the code below the gate were wrong.
        verify(holder, never()).tradeClient(any());
    }

    @Test
    @DisplayName("production is still refused when only the boolean is set")
    void productionRefusedWithoutTheConfirmationPhrase() {
        OrderSubmissionService service = serviceWith(gate(true, "yes please"));
        account(TradingEnvironment.PRODUCTION, "INDIVIDUAL_MARGIN");

        SubmissionResult result = service.submit(
                TradingEnvironment.PRODUCTION, "acct", viablePlan());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusedReason()).contains("confirmation");
        verify(holder, never()).tradeClient(any());
    }

    @Test
    @DisplayName("the sandbox is never gated - it is paper")
    void sandboxIsNotGated() {
        OrderSubmissionService service = serviceWith(gate(false, ""));
        account(TradingEnvironment.SANDBOX, "INDIVIDUAL_MARGIN");
        when(holder.tradeClient(TradingEnvironment.SANDBOX)).thenReturn(Optional.empty());
        when(holder.unavailableReason(TradingEnvironment.SANDBOX))
                .thenReturn(Optional.of("not connected"));

        SubmissionResult result = service.submit(
                TradingEnvironment.SANDBOX, "acct", viablePlan());

        // It gets past the live gate and fails on the connection instead - which is what proves
        // the gate did not apply.
        assertThat(result.refusedReason()).doesNotContain("allow-live-trading");
        assertThat(result.refusedReason()).contains("No trading connection");
        verify(holder).tradeClient(TradingEnvironment.SANDBOX);
    }

    @Test
    @DisplayName("a plan the planner marked unviable is never submitted")
    void unviablePlanIsRefused() {
        OrderSubmissionService service = serviceWith(gate(false, ""));
        account(TradingEnvironment.SANDBOX, "INDIVIDUAL_MARGIN");

        SubmissionResult result = service.submit(
                TradingEnvironment.SANDBOX, "acct", unviablePlan());

        // Otherwise the viability check would be decorative: flagged in the UI and ignored here.
        assertThat(result.accepted()).isFalse();
        assertThat(result.refusedReason()).contains("unviable");
        verify(holder, never()).tradeClient(any());
    }

    @Test
    @DisplayName("an unknown account is refused before anything is built")
    void unknownAccountIsRefused() {
        OrderSubmissionService service = serviceWith(gate(false, ""));
        when(accounts.findAccount(any(), any())).thenReturn(Optional.empty());

        SubmissionResult result = service.submit(
                TradingEnvironment.SANDBOX, "nope", viablePlan());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusedReason()).contains("No such account");
    }

    @Test
    @DisplayName("a market entry outside regular hours is refused, with a reason")
    void marketOrderOutsideRegularHoursIsRefused() {
        // The venue only accepts limit orders in extended sessions, so this is decided here
        // rather than discovered from a vendor error code.
        when(clock.isRegularHours()).thenReturn(false);
        when(clock.currentSession()).thenReturn(dev.aperture.time.TradingSession.POST_MARKET);
        OrderSubmissionService service = serviceWith(gate(false, ""));
        account(TradingEnvironment.SANDBOX, "INDIVIDUAL_MARGIN");
        when(holder.tradeClient(TradingEnvironment.SANDBOX))
                .thenReturn(Optional.of(mock(com.webull.openapi.trade.TradeClientV3.class)));

        SubmissionResult result = service.submit(
                TradingEnvironment.SANDBOX, "acct", viablePlan());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusedReason())
                .contains("market is closed")
                .contains("marketable limit");
    }

    // --- helpers ---

    private OrderSubmissionService serviceWith(ApertureProperties.Webull webull) {
        ApertureProperties properties = new ApertureProperties(
                webull,
                new ApertureProperties.MarketData(List.of(), List.of(), Duration.ofSeconds(2),
                        Duration.ofSeconds(15), 250, false, Duration.ofMinutes(5)),
                new ApertureProperties.Analyst(false, "m", "", 8, 16000));
        return new OrderSubmissionService(holder, accounts, clock, properties);
    }

    private static ApertureProperties.Webull gate(boolean allow, String confirmation) {
        return new ApertureProperties.Webull("k", "s", "", "", "us", true, "SANDBOX",
                allow, confirmation, Duration.ofMinutes(6));
    }

    private void account(TradingEnvironment environment, String accountClass) {
        when(accounts.findAccount(any(), any())).thenReturn(Optional.of(new BrokerAccount(
                "acct", "12345678", "MARGIN", accountClass, "Test", environment)));
    }

    private static TradeRecommendation viablePlan() {
        return plan(true);
    }

    private static TradeRecommendation unviablePlan() {
        return plan(false);
    }

    private static TradeRecommendation plan(boolean viable) {
        TradeEconomics economics = new TradeEconomics(
                Price.of(100), Price.of(110), Money.usd(1000), Money.usd(100),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
                Optional.empty(), Optional.empty(), Optional.empty(),
                BigDecimal.valueOf(-5), Optional.empty(),
                viable ? List.of() : List.of("target is at or below the entry"), viable);
        return new TradeRecommendation("TEST", "Test", "EQUITY", "HIGH", "because", 21,
                List.of(OrderLeg.marketBuy(Quantity.of(10), "in"),
                        OrderLeg.gtcSellLimit(Quantity.of(10), Price.of(110), "out")),
                economics);
    }
}
