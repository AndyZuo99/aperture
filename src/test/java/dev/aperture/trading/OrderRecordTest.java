package dev.aperture.trading;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metrics an order log is worth having for.
 *
 * <p>The vendor returns what was asked for and what happened. Everything here is the difference
 * between the two, which is the part that says whether the execution was any good. Figures are
 * pinned against real fills from the live account so they can be checked by hand.
 */
class OrderRecordTest {

    private static final Instant PLACED = Instant.parse("2026-09-11T22:26:11.720Z");
    private static final Instant FILLED = Instant.parse("2026-09-11T22:26:16.446Z");

    @Test
    @DisplayName("a buy filled below its limit is positive improvement")
    void buyFilledBelowLimitIsImprovement() {
        // The real fill: 55 MRK asked at 145.19, filled at 144.33.
        OrderRecord order = filledBuy("MRK", 55, "145.19", "144.33");

        assertThat(order.priceImprovement()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("0.86"));
        assertThat(order.priceImprovementPercent())
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0.592"));
        assertThat(order.filledNotional()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("7938.15"));
    }

    @Test
    @DisplayName("a sell filled ABOVE its limit is positive improvement too")
    void sellFilledAboveLimitIsAlsoImprovement() {
        // The sign inverts with the side. A single "filled minus limit" would report this
        // identical outcome as a loss purely because it was a sell.
        OrderRecord sell = order("SPY", "SELL", "LIMIT", "GTC", OrderStatus.FILLED,
                10, 10, "50.00", "50.40", Optional.of(FILLED));

        assertThat(sell.priceImprovement()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("0.40"));

        // And a sell that had to give up price is negative, not silently positive.
        OrderRecord conceded = order("SPY", "SELL", "LIMIT", "GTC", OrderStatus.FILLED,
                10, 10, "50.00", "49.60", Optional.of(FILLED));
        assertThat(conceded.priceImprovement()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("-0.40"));
    }

    @Test
    @DisplayName("a market order has no price improvement rather than a huge one")
    void marketOrdersHaveNoImprovement() {
        // There is no limit to have beaten. Comparing against zero would report the entire fill
        // price as improvement - the SLB market buy would have shown +54.24 a share.
        OrderRecord market = order("SLB", "BUY", "MARKET", "DAY", OrderStatus.FILLED,
                3, 3, null, "54.24", Optional.of(FILLED));

        assertThat(market.limitPrice()).isEmpty();
        assertThat(market.priceImprovement()).isEmpty();
        assertThat(market.priceImprovementPercent()).isEmpty();
        // The fill itself is still measured.
        assertThat(market.filledNotional()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("162.72"));
    }

    @Test
    @DisplayName("an unfilled order has no improvement and no fill time")
    void unfilledOrdersHaveNoExecutionMetrics() {
        // A resting GTC exit: it has a limit but has not traded, so there is nothing to compare.
        OrderRecord working = order("SLB", "SELL", "LIMIT", "GTC", OrderStatus.WORKING,
                3, 0, "56.40", null, Optional.empty());

        assertThat(working.priceImprovement()).isEmpty();
        assertThat(working.filledNotional()).isEmpty();
        assertThat(working.timeToFill()).isEmpty();
        assertThat(working.fillRatePercent()).isEqualByComparingTo("0.0");
        assertThat(working.remainingQuantity()).isEqualTo(Quantity.of(3));
    }

    @Test
    @DisplayName("fill rate and remaining quantity follow a partial fill")
    void partialFill() {
        OrderRecord partial = order("AAPL", "BUY", "LIMIT", "DAY", OrderStatus.PARTIALLY_FILLED,
                10, 3, "333.00", "332.50", Optional.of(FILLED));

        assertThat(partial.fillRatePercent()).isEqualByComparingTo("30.0");
        assertThat(partial.remainingQuantity()).isEqualTo(Quantity.of(7));
        // Notional is what actually moved, not what was asked for: 3 shares, not 10.
        assertThat(partial.filledNotional()).hasValueSatisfying(
                m -> assertThat(m.amount()).isEqualByComparingTo("997.50"));
    }

    @Test
    @DisplayName("time to fill is measured, including the sub-second fills that are most of them")
    void timeToFill() {
        assertThat(filledBuy("MRK", 55, "145.19", "144.33").timeToFill())
                .contains(Duration.ofMillis(4726));
    }

    @Test
    @DisplayName("a resting exit is an open GTC sell, and nothing else is")
    void restingExitDetection() {
        // The row worth finding: it is what makes a filled entry a managed position.
        assertThat(order("SLB", "SELL", "LIMIT", "GTC", OrderStatus.WORKING,
                3, 0, "56.40", null, Optional.empty()).isRestingExit()).isTrue();

        // A cancelled GTC sell is not resting - the position it was protecting is now naked.
        assertThat(order("MRK", "SELL", "LIMIT", "GTC", OrderStatus.CANCELLED,
                55, 0, "148.50", null, Optional.empty()).isRestingExit()).isFalse();
        // Nor is a day order, which expires at the close.
        assertThat(order("MRK", "SELL", "LIMIT", "DAY", OrderStatus.WORKING,
                55, 0, "148.50", null, Optional.empty()).isRestingExit()).isFalse();
        // Nor is an open buy.
        assertThat(order("MRK", "BUY", "LIMIT", "GTC", OrderStatus.WORKING,
                55, 0, "140.00", null, Optional.empty()).isRestingExit()).isFalse();
    }

    @Test
    @DisplayName("a zero-quantity order does not divide by zero")
    void zeroQuantityIsSafe() {
        OrderRecord empty = order("X", "BUY", "LIMIT", "DAY", OrderStatus.REJECTED,
                0, 0, "10.00", null, Optional.empty());

        assertThat(empty.fillRatePercent()).isEqualByComparingTo("0");
        assertThat(empty.remainingQuantity()).isEqualTo(Quantity.zero());
    }

    @Test
    @DisplayName("commission and fees add up, and absent ones are zero rather than missing")
    void totalCost() {
        OrderRecord order = new OrderRecord("id", "client", "AAPL", "EQUITY", Optional.empty(),
                "BUY", "LIMIT", "DAY", OrderStatus.FILLED, "FILLED",
                Quantity.of(1), Quantity.of(1), Optional.of(Price.of(100)), Optional.empty(),
                Optional.of(Price.of(99)), PLACED, Optional.of(FILLED),
                Optional.of(Money.usd(new BigDecimal("1.25"))),
                Optional.of(Money.usd(new BigDecimal("0.30"))));

        assertThat(order.totalCost().amount()).isEqualByComparingTo("1.55");
        assertThat(filledBuy("MRK", 55, "145.19", "144.33").totalCost()).isEqualTo(Money.zero());
    }

    private static OrderRecord filledBuy(String symbol, long qty, String limit, String filled) {
        return order(symbol, "BUY", "LIMIT", "DAY", OrderStatus.FILLED,
                qty, qty, limit, filled, Optional.of(FILLED));
    }

    private static OrderRecord order(String symbol, String side, String type, String tif,
                                     OrderStatus status, long total, long filled,
                                     String limit, String filledPrice, Optional<Instant> filledAt) {
        return new OrderRecord(
                "order-" + symbol + "-" + side + "-" + status, "client", symbol, "EQUITY",
                Optional.empty(), side, type, tif, status, status.name(),
                Quantity.of(total), Quantity.of(filled),
                limit == null ? Optional.empty() : Optional.of(Price.of(new BigDecimal(limit))),
                Optional.empty(),
                filledPrice == null ? Optional.empty()
                        : Optional.of(Price.of(new BigDecimal(filledPrice))),
                PLACED, filledAt, Optional.empty(), Optional.empty());
    }
}
