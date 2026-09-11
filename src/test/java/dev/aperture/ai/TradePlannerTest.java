package dev.aperture.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aperture.analysis.HistoryProvider;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.Quote;
import dev.aperture.marketdata.QuoteProvenance;
import dev.aperture.time.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checks that stand between a model's proposal and a plan presented as sound.
 *
 * <p>These are the ones a language model does not reliably run on itself: that the target is
 * actually above the price you would pay, and that the gain clears the spread you cross to get in.
 * A plan failing either is arithmetic nonsense however good the reasoning sounds, so it is marked
 * unviable here rather than rendered with a confident profit figure.
 */
class TradePlannerTest {

    private static final InstrumentId ID = InstrumentId.of("TEST");

    private MarketDataService marketData;
    private HistoryProvider history;
    private TradePlanner planner;

    @BeforeEach
    void setUp() {
        marketData = mock(MarketDataService.class);
        history = mock(HistoryProvider.class);
        planner = new TradePlanner(marketData, history);
        when(history.bars(anyString(), any())).thenReturn(flatHistory());
    }

    @Test
    @DisplayName("the entry is the ASK, because a market buy lifts the offer")
    void entryIsTheAsk() {
        // Using the last print would understate the cost of every recommendation.
        quoted(99.00, 101.00, 100.00);

        TradeRecommendation plan = plan(110.00);

        assertThat(plan.economics().entryPrice().toDisplay())
                .isEqualByComparingTo(BigDecimal.valueOf(101));
    }

    @Test
    @DisplayName("profit and return are computed from the entry, not asserted by the model")
    void economicsAreDerived() {
        quoted(99.00, 100.00, 100.00);

        TradeRecommendation plan = plan(110.00, 50);

        // (110 - 100) * 50 = 500
        assertThat(plan.economics().grossProfit().toDisplay())
                .isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(plan.economics().notional().toDisplay())
                .isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(plan.economics().returnPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(10));
    }

    @Test
    @DisplayName("a target at or below the entry is rejected as unviable")
    void targetBelowEntryIsNotViable() {
        quoted(99.00, 101.00, 100.00);

        TradeRecommendation plan = plan(100.00);

        assertThat(plan.economics().viable()).isFalse();
        assertThat(plan.economics().warnings())
                .anyMatch(warning -> warning.contains("at or below the entry"));
    }

    @Test
    @DisplayName("a gain smaller than the spread it must cross is rejected")
    void gainBelowSpreadIsNotViable() {
        // A 10% spread with a 1% target: the position is under water the instant it is opened.
        quoted(95.00, 105.00, 100.00);

        TradeRecommendation plan = plan(106.00);

        assertThat(plan.economics().viable()).isFalse();
        assertThat(plan.economics().warnings())
                .anyMatch(warning -> warning.contains("cost of crossing the spread"));
    }

    @Test
    @DisplayName("the two legs are a market buy and a GTC sell limit at the target")
    void legsAreMarketBuyAndGtcSellLimit() {
        quoted(99.00, 100.00, 100.00);

        TradeRecommendation plan = plan(110.00, 10);

        assertThat(plan.legs()).hasSize(2);
        OrderLeg entry = plan.legs().get(0);
        assertThat(entry.side()).isEqualTo(OrderLeg.Side.BUY);
        assertThat(entry.type()).isEqualTo(OrderLeg.Type.MARKET);
        assertThat(entry.limitPrice()).isEmpty();

        OrderLeg exit = plan.legs().get(1);
        assertThat(exit.side()).isEqualTo(OrderLeg.Side.SELL);
        assertThat(exit.type()).isEqualTo(OrderLeg.Type.LIMIT);
        // GTC, not DAY: the thesis runs for weeks, and a day order would expire the same
        // afternoon leaving the position with no exit resting at all.
        assertThat(exit.timeInForce()).isEqualTo(OrderLeg.TimeInForce.GTC);
        assertThat(exit.limitPrice()).contains(Price.of(110.00));
    }

    @Test
    @DisplayName("simulated prices are flagged on the plan itself")
    void simulatedPricesAreFlagged() {
        when(marketData.quoteOnDemand(anyString())).thenReturn(Optional.of(
                quote(99.00, 100.00, 100.00, QuoteProvenance.SIMULATED)));

        TradeRecommendation plan = plan(110.00);

        assertThat(plan.economics().warnings())
                .anyMatch(warning -> warning.contains("simulated"));
    }

    @Test
    @DisplayName("with no history the hit rate is absent, not invented")
    void missingHistoryLeavesHitRateEmpty() {
        quoted(99.00, 100.00, 100.00);
        when(history.bars(anyString(), any())).thenReturn(List.of());

        TradeRecommendation plan = plan(110.00);

        assertThat(plan.economics().historicalHitRatePercent()).isEmpty();
        assertThat(plan.economics().warnings())
                .anyMatch(warning -> warning.contains("No price history"));
    }

    @Test
    @DisplayName("a futures proposal explains that the data entitlement is missing")
    void futuresExplainTheEntitlementGap() {
        quoted(99.00, 100.00, 100.00);
        when(history.bars(anyString(), any())).thenReturn(List.of());

        TradeRecommendation plan = planner.plan("ESZ6", "E-mini", TradableUniverse.FUTURES,
                        Quantity.of(1), Price.of(110), 21, "HIGH", "because")
                .orElseThrow();

        assertThat(plan.economics().warnings())
                .anyMatch(warning -> warning.contains("separate Webull entitlement"));
    }

    @Test
    @DisplayName("with no quote and no history there is nothing to price against")
    void noQuoteAndNoHistoryYieldsNoPlan() {
        when(marketData.quoteOnDemand(anyString())).thenReturn(Optional.empty());
        when(history.bars(anyString(), any())).thenReturn(List.of());

        assertThat(planner.plan("TEST", "Test", TradableUniverse.EQUITY,
                Quantity.of(10), Price.of(110), 21, "HIGH", "because")).isEmpty();
    }

    // --- helpers ---

    private TradeRecommendation plan(double target) {
        return plan(target, 10);
    }

    private TradeRecommendation plan(double target, long quantity) {
        return planner.plan("TEST", "Test Inc", TradableUniverse.EQUITY,
                        Quantity.of(quantity), Price.of(target), 21, "HIGH", "because")
                .orElseThrow();
    }

    /**
     * Stubs the on-demand path, which is what the planner calls.
     *
     * <p>Not {@code quote(...)}: a shortlisted candidate is not streamed, so the planner fetches
     * its quote on demand rather than reading the cache.
     */
    private void quoted(double bid, double ask, double last) {
        when(marketData.quoteOnDemand(anyString()))
                .thenReturn(Optional.of(quote(bid, ask, last, QuoteProvenance.LIVE_STREAM)));
    }

    private static Quote quote(double bid, double ask, double last, QuoteProvenance provenance) {
        Instant now = Instant.parse("2026-09-11T14:00:00Z");
        return new Quote(ID, Price.of(bid), Quantity.of(100), Price.of(ask), Quantity.of(100),
                Price.of(last), Quantity.of(1_000_000L), Price.of(last),
                TradingSession.REGULAR, provenance, now, now);
    }

    private static List<Bar> flatHistory() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            bars.add(new Bar(ID, LocalDate.parse("2026-01-05").plusDays(i),
                    Price.of(100), Price.of(102), Price.of(98), Price.of(100),
                    Quantity.of(1_000_000L), PriceBasis.SPLIT_ADJUSTED));
        }
        return bars;
    }
}
