package dev.aperture.trading;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Execution quality aggregated across an account's orders.
 *
 * <p>Every average here has to state the population it runs over, and the populations differ:
 * averaging fill time across cancelled orders would count them as instant, and averaging price
 * improvement across market orders would average in a figure that does not exist.
 */
class OrderMetricsTest {

    private static final Instant PLACED = Instant.parse("2026-09-14T14:53:00.000Z");

    /** The production Individual Margin account: two market entries and two resting GTC exits. */
    private static List<OrderRecord> productionAccount() {
        return List.of(
                order("SLB", "SELL", "LIMIT", "GTC", OrderStatus.WORKING, 3, 0, "56.40", null, 0),
                order("SLB", "BUY", "MARKET", "DAY", OrderStatus.FILLED, 3, 3, null, "54.24", 39),
                order("HAL", "SELL", "LIMIT", "GTC", OrderStatus.WORKING, 6, 0, "37.04", null, 0),
                order("HAL", "BUY", "MARKET", "DAY", OrderStatus.FILLED, 6, 6, null, "35.27", 33));
    }

    @Test
    @DisplayName("orders are counted by outcome")
    void countsByOutcome() {
        OrderMetrics metrics = OrderMetrics.of(productionAccount());

        assertThat(metrics.totalOrders()).isEqualTo(4);
        assertThat(metrics.filled()).isEqualTo(2);
        assertThat(metrics.working()).isEqualTo(2);
        assertThat(metrics.cancelled()).isZero();
        assertThat(metrics.rejected()).isZero();
        assertThat(metrics.distinctSymbols()).isEqualTo(2);
        assertThat(metrics.filledOrderPercent()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("resting exits are counted, because their absence is the thing to notice")
    void countsRestingExits() {
        // Two filled entries, two live exits: every position is protected. One of each would mean
        // a naked position, which looks identical in a positions panel.
        assertThat(OrderMetrics.of(productionAccount()).restingExits()).isEqualTo(2);
    }

    @Test
    @DisplayName("notional counts what filled, not what was asked for")
    void notionalIsFilledOnly() {
        // 3 x 54.24 + 6 x 35.27. The two resting exits contribute nothing - they have not traded,
        // and adding their intended size would roughly double the account's apparent activity.
        assertThat(OrderMetrics.of(productionAccount()).filledNotional().amount())
                .isEqualByComparingTo("374.34");
        assertThat(OrderMetrics.of(productionAccount()).ordersWithFills()).isEqualTo(2);
    }

    @Test
    @DisplayName("average fill time covers only the orders that filled")
    void averageFillTimeExcludesUnfilled() {
        // 39ms and 33ms. Counting the two resting exits as zero would report 18ms and make
        // execution look twice as fast as it was.
        assertThat(OrderMetrics.of(productionAccount()).averageTimeToFill())
                .contains(Duration.ofMillis(36));
    }

    @Test
    @DisplayName("price improvement covers only orders that had a limit to beat")
    void improvementExcludesMarketOrders() {
        // Both fills above are market orders, so there is no improvement figure at all - not zero,
        // which would read as "we got exactly our price".
        OrderMetrics marketOnly = OrderMetrics.of(productionAccount());
        assertThat(marketOnly.averagePriceImprovementPercent()).isEmpty();
        assertThat(marketOnly.totalPriceImprovement()).isEqualTo(Money.zero());

        // With limit fills it is the money saved: 0.86 x 55 + 0.71 x 1.
        OrderMetrics withLimits = OrderMetrics.of(List.of(
                order("MRK", "BUY", "LIMIT", "DAY", OrderStatus.FILLED, 55, 55,
                        "145.19", "144.33", 4726),
                order("AAPL", "BUY", "LIMIT", "DAY", OrderStatus.FILLED, 1, 1,
                        "333.36", "332.65", 3680)));

        assertThat(withLimits.totalPriceImprovement().amount()).isEqualByComparingTo("48.01");
        assertThat(withLimits.averagePriceImprovementPercent())
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0.402"));
    }

    @Test
    @DisplayName("an account that has never traded produces zeroes, not errors")
    void emptyAccount() {
        OrderMetrics metrics = OrderMetrics.empty();

        assertThat(metrics.totalOrders()).isZero();
        assertThat(metrics.filledOrderPercent()).isEqualByComparingTo("0");
        assertThat(metrics.filledNotional()).isEqualTo(Money.zero());
        assertThat(metrics.averageTimeToFill()).isEmpty();
        assertThat(metrics.averagePriceImprovementPercent()).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised vendor status is counted in the total but in no outcome")
    void unknownStatusIsNotMiscounted() {
        // A status this build has not seen must not be quietly counted as a fill.
        OrderMetrics metrics = OrderMetrics.of(List.of(
                order("X", "BUY", "LIMIT", "DAY", OrderStatus.UNKNOWN, 1, 0, "10.00", null, 0)));

        assertThat(metrics.totalOrders()).isEqualTo(1);
        assertThat(metrics.filled() + metrics.working() + metrics.cancelled()
                + metrics.rejected() + metrics.partiallyFilled()).isZero();
    }

    private static OrderRecord order(String symbol, String side, String type, String tif,
                                     OrderStatus status, long total, long filled,
                                     String limit, String filledPrice, long fillMillis) {
        return new OrderRecord(
                "id-" + symbol + "-" + side, "client", symbol, "EQUITY", Optional.empty(),
                side, type, tif, status, status.name(),
                Quantity.of(total), Quantity.of(filled),
                limit == null ? Optional.empty() : Optional.of(Price.of(new BigDecimal(limit))),
                Optional.empty(),
                filledPrice == null ? Optional.empty()
                        : Optional.of(Price.of(new BigDecimal(filledPrice))),
                PLACED,
                filledPrice == null ? Optional.empty()
                        : Optional.of(PLACED.plusMillis(fillMillis)),
                Optional.empty(), Optional.empty());
    }
}
