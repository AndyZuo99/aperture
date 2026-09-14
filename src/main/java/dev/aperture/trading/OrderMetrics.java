package dev.aperture.trading;

import dev.aperture.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Execution quality across a set of orders.
 *
 * <p>Aggregates deliberately drawn from orders that actually executed. Averaging fill time over
 * every order would include the cancelled ones as zero and make execution look instant; averaging
 * price improvement over market orders would average in a value that does not exist. Each figure
 * here states the population it was computed over.
 *
 * @param ordersWithFills how many orders contributed to the execution averages
 */
public record OrderMetrics(
        int totalOrders,
        int filled,
        int partiallyFilled,
        int working,
        int cancelled,
        int rejected,
        int restingExits,
        int distinctSymbols,
        int ordersWithFills,
        BigDecimal filledOrderPercent,
        Money filledNotional,
        Money totalCost,
        Optional<Duration> averageTimeToFill,
        Optional<BigDecimal> averagePriceImprovementPercent,
        Money totalPriceImprovement) {

    public static OrderMetrics of(List<OrderRecord> orders) {
        int filled = 0;
        int partiallyFilled = 0;
        int working = 0;
        int cancelled = 0;
        int rejected = 0;
        int restingExits = 0;
        int withFills = 0;
        int timedFills = 0;
        int improvementCount = 0;

        Money notional = Money.zero();
        Money cost = Money.zero();
        Money improvement = Money.zero();
        long fillMillis = 0;
        BigDecimal improvementPercent = BigDecimal.ZERO;
        Set<String> symbols = new HashSet<>();

        for (OrderRecord order : orders) {
            symbols.add(order.symbol());
            switch (order.status()) {
                case FILLED -> filled++;
                case PARTIALLY_FILLED -> partiallyFilled++;
                case WORKING -> working++;
                case CANCELLED -> cancelled++;
                case REJECTED -> rejected++;
                case UNKNOWN -> { }
            }
            if (order.isRestingExit()) {
                restingExits++;
            }
            cost = cost.plus(order.totalCost());

            Optional<Money> filledNotional = order.filledNotional();
            if (filledNotional.isPresent()) {
                withFills++;
                notional = notional.plus(filledNotional.get());
            }
            Optional<Duration> toFill = order.timeToFill();
            if (toFill.isPresent()) {
                timedFills++;
                fillMillis += toFill.get().toMillis();
            }
            Optional<BigDecimal> percent = order.priceImprovementPercent();
            if (percent.isPresent()) {
                improvementCount++;
                improvementPercent = improvementPercent.add(percent.get());
                // Per-share improvement times the shares it applied to: what was actually saved.
                improvement = improvement.plus(order.priceImprovement().orElseThrow()
                        .times(order.filledQuantity().value()));
            }
        }

        BigDecimal filledPercent = orders.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(filled).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(orders.size()), 1, RoundingMode.HALF_EVEN);

        return new OrderMetrics(
                orders.size(), filled, partiallyFilled, working, cancelled, rejected,
                restingExits, symbols.size(), withFills,
                filledPercent,
                notional,
                cost,
                timedFills == 0 ? Optional.empty()
                        : Optional.of(Duration.ofMillis(fillMillis / timedFills)),
                improvementCount == 0 ? Optional.empty()
                        : Optional.of(improvementPercent.divide(
                                BigDecimal.valueOf(improvementCount), 3, RoundingMode.HALF_EVEN)),
                Money.usd(improvement.amount().setScale(2, RoundingMode.HALF_EVEN)));
    }

    public static OrderMetrics empty() {
        return of(List.of());
    }
}
