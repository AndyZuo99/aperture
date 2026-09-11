package dev.aperture.ai;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import java.util.Objects;
import java.util.Optional;

/**
 * One leg of a proposed trade, in the shape an order ticket would take.
 *
 * <p>A recommendation is expressed as concrete orders rather than a view, because "buy AAPL, it
 * looks strong" is not actionable and cannot be scored afterwards. A side, a quantity, an order
 * type and a time-in-force can be entered as-is and checked later against what actually happened.
 *
 * <p>These are <strong>proposals</strong>. Nothing in Aperture submits them - see
 * {@code AnalystToolkit}, which has no mutating method at all.
 */
public record OrderLeg(
        Side side,
        Type type,
        Quantity quantity,
        Optional<Price> limitPrice,
        TimeInForce timeInForce,
        String purpose) {

    public OrderLeg {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(timeInForce, "timeInForce");
        purpose = purpose == null ? "" : purpose;
    }

    /** The entry: buy now, at whatever the market is. */
    public static OrderLeg marketBuy(Quantity quantity, String purpose) {
        return new OrderLeg(Side.BUY, Type.MARKET, quantity, Optional.empty(),
                TimeInForce.DAY, purpose);
    }

    /**
     * The exit: a resting sell limit at the target.
     *
     * <p>Good-til-cancelled because the thesis has a horizon of weeks. A day order would expire
     * the same afternoon and quietly leave the position unhedged with no exit at all.
     */
    public static OrderLeg gtcSellLimit(Quantity quantity, Price target, String purpose) {
        return new OrderLeg(Side.SELL, Type.LIMIT, quantity, Optional.of(target),
                TimeInForce.GTC, purpose);
    }

    public String describe() {
        StringBuilder text = new StringBuilder()
                .append(side).append(' ').append(quantity.toDisplay().toPlainString())
                .append(' ').append(type);
        limitPrice.ifPresent(price -> text.append(" @ ").append(price.toDisplay().toPlainString()));
        return text.append(" (").append(timeInForce).append(')').toString();
    }

    public enum Side { BUY, SELL }

    public enum Type { MARKET, LIMIT }

    /**
     * Note the vendor supports DAY, GTC, GTD and IOC only - there is no fill-or-kill on the
     * Webull OpenAPI.
     */
    public enum TimeInForce { DAY, GTC }
}
