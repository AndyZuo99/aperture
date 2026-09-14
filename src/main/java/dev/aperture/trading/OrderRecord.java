package dev.aperture.trading;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One order, with the metrics the vendor does not calculate.
 *
 * <p>The raw fields are a record of what was asked for and what happened. The derived ones are the
 * reason to have this panel at all: whether an order filled, how long it took, what it actually
 * cost against what it was allowed to cost, and how much capital it moved. A list of order rows is
 * a receipt; these are what make it a review of execution quality.
 *
 * @param totalQuantity the order's full size. Read from the vendor's {@code totalQuantity} - its
 *     {@code quantity} field is deprecated and comes back null on every order observed.
 * @param filledPrice the average execution price, absent until something executes
 */
public record OrderRecord(
        String orderId,
        String clientOrderId,
        String symbol,
        String instrumentType,
        /** YES or NO for an event contract, which trades as two separate instruments. */
        Optional<String> eventOutcome,
        String side,
        String orderType,
        String timeInForce,
        OrderStatus status,
        String vendorStatus,
        Quantity totalQuantity,
        Quantity filledQuantity,
        Optional<Price> limitPrice,
        Optional<Price> stopPrice,
        Optional<Price> filledPrice,
        Instant placedAt,
        Optional<Instant> filledAt,
        Optional<Money> commission,
        Optional<Money> fees) {

    public OrderRecord {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(placedAt, "placedAt");
        symbol = symbol.toUpperCase();
    }

    /** Whether this is a buy. Used for the sign of price improvement, where it inverts. */
    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(side);
    }

    /** How much of the order executed, 0 to 100. */
    public BigDecimal fillRatePercent() {
        BigDecimal total = totalQuantity.value();
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return filledQuantity.value().multiply(BigDecimal.valueOf(100))
                .divide(total, 1, RoundingMode.HALF_EVEN);
    }

    /** The quantity still live at the venue. */
    public Quantity remainingQuantity() {
        BigDecimal remaining = totalQuantity.value().subtract(filledQuantity.value());
        return Quantity.of(remaining.signum() < 0 ? BigDecimal.ZERO : remaining);
    }

    /**
     * Capital actually moved: filled quantity times the average fill price.
     *
     * <p>Not the order's notional at the limit - an order that filled a tenth of its size moved a
     * tenth of the money, and totalling the intended sizes would overstate the account's activity.
     */
    public Optional<Money> filledNotional() {
        return filledPrice
                .filter(price -> filledQuantity.value().signum() > 0)
                .map(price -> Money.usd(price.value().multiply(filledQuantity.value())
                        .setScale(2, RoundingMode.HALF_EVEN)));
    }

    /** How long the venue took to fill it, for orders that filled. */
    public Optional<Duration> timeToFill() {
        return filledAt.map(filled -> Duration.between(placedAt, filled))
                .filter(duration -> !duration.isNegative());
    }

    /**
     * How much better than the limit the order actually executed, per share.
     *
     * <p>Positive is always in the trader's favour, which means the sign inverts with the side: a
     * buy filled <em>below</em> its limit and a sell filled <em>above</em> it are both good. A
     * single signed "filled minus limit" would call one of them a loss.
     *
     * <p>Empty for market orders. There is no limit to have beaten, and comparing against zero
     * would report the entire price as improvement.
     */
    public Optional<Money> priceImprovement() {
        if (limitPrice.isEmpty() || filledPrice.isEmpty() || filledQuantity.value().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal limit = limitPrice.get().value();
        BigDecimal filled = filledPrice.get().value();
        if (limit.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal perShare = isBuy() ? limit.subtract(filled) : filled.subtract(limit);
        return Optional.of(Money.usd(perShare.setScale(4, RoundingMode.HALF_EVEN)));
    }

    /** Price improvement as a percentage of the limit, which compares across price levels. */
    public Optional<BigDecimal> priceImprovementPercent() {
        return priceImprovement().map(improvement ->
                improvement.amount().multiply(BigDecimal.valueOf(100))
                        .divide(limitPrice.orElseThrow().value(), 3, RoundingMode.HALF_EVEN));
    }

    /** Commission and fees together, the all-in cost of having placed it. */
    public Money totalCost() {
        return commission.orElse(Money.zero()).plus(fees.orElse(Money.zero()));
    }

    /**
     * A resting exit: an open sell that is good-til-cancelled.
     *
     * <p>Called out because its absence is what turns a filled entry into an unmanaged position -
     * exactly the state the recommender's two-leg plan exists to avoid.
     */
    public boolean isRestingExit() {
        return status.isOpen() && !isBuy() && "GTC".equalsIgnoreCase(timeInForce);
    }
}
