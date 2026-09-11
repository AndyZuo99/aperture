package dev.aperture.corporate;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.instrument.InstrumentId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An event that changes the meaning of an instrument's price or share count.
 *
 * <p>This is the type that stops a chart from lying. When NVDA split 10-for-1 on 2024-06-10, the
 * raw close went from ~1208 to ~121. Plotted unadjusted that is a 90% crash; every return
 * calculation crossing that date is off by a factor of ten. The fix is to restate history in
 * today's share terms - see {@link PriceAdjuster}.
 *
 * <p>Sealed because the adjustment engine switches exhaustively over the variants. Adding a new
 * action type should fail to compile until every site that must handle it does.
 *
 * <p>Each action exposes two independent factors:
 * <ul>
 *   <li>{@link #priceAdjustmentFactor(Price)} - multiply historical prices before the ex-date by
 *       this to restate them in post-action terms.
 *   <li>{@link #quantityAdjustmentFactor()} - multiply a held share count by this on the ex-date.
 * </ul>
 * They are separate because they are not always reciprocal: a cash dividend moves the price but
 * not the share count, and a symbol change moves neither.
 */
public sealed interface CorporateAction
        permits CorporateAction.Split,
                CorporateAction.CashDividend,
                CorporateAction.StockDividend,
                CorporateAction.SymbolChange {

    /**
     * Scale for adjustment factors. Deliberately high: factors compound multiplicatively across
     * every action in a series, so rounding each one to a few places visibly distorts a long
     * history.
     */
    int ADJUSTMENT_SCALE = 12;

    InstrumentId instrumentId();

    /**
     * The first date the security trades <em>without</em> the entitlement. This is the boundary:
     * bars strictly before it are in old terms and need adjusting, bars on or after it are
     * already in new terms.
     */
    LocalDate exDate();

    /**
     * Factor to multiply pre-ex-date prices by.
     *
     * @param previousClose the close on the last trading day before the ex-date. Only cash
     *     dividends use it - the adjustment is proportional to the price the dividend was paid
     *     out of, so the same $1 dividend is a larger adjustment on a $20 stock than a $200 one.
     *     Passing an arbitrary price here produces a factor that silently changes with when you
     *     asked.
     */
    BigDecimal priceAdjustmentFactor(Price previousClose);

    /** Factor to multiply a held share count by on the ex-date. One for everything but splits. */
    BigDecimal quantityAdjustmentFactor();

    /** Human-readable summary, shown on the chart and in the corporate-actions panel. */
    String describe();

    /** Whether this action changes how many shares a holder owns. */
    default boolean affectsShareCount() {
        return quantityAdjustmentFactor().compareTo(BigDecimal.ONE) != 0;
    }

    /** Whether this action restates historical prices. */
    default boolean affectsPrices() {
        return !(this instanceof SymbolChange);
    }

    /**
     * A forward or reverse split. {@code newShares}-for-{@code oldShares}: a 10-for-1 split is
     * {@code newShares=10, oldShares=1}, and a 1-for-8 reverse split is {@code newShares=1,
     * oldShares=8}.
     */
    record Split(InstrumentId instrumentId, LocalDate exDate, BigDecimal newShares,
                 BigDecimal oldShares) implements CorporateAction {

        public Split {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(exDate, "exDate");
            Objects.requireNonNull(newShares, "newShares");
            Objects.requireNonNull(oldShares, "oldShares");
            if (newShares.signum() <= 0 || oldShares.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Split ratio must be positive: " + newShares + ":" + oldShares);
            }
        }

        public static Split forward(InstrumentId id, LocalDate exDate, long newShares, long oldShares) {
            return new Split(id, exDate, BigDecimal.valueOf(newShares), BigDecimal.valueOf(oldShares));
        }

        /** A 10-for-1 split divides historical prices by 10. */
        @Override
        public BigDecimal priceAdjustmentFactor(Price previousClose) {
            return oldShares.divide(newShares, ADJUSTMENT_SCALE, RoundingMode.HALF_EVEN);
        }

        /** ...and multiplies the share count by 10. */
        @Override
        public BigDecimal quantityAdjustmentFactor() {
            return newShares.divide(oldShares, ADJUSTMENT_SCALE, RoundingMode.HALF_EVEN);
        }

        public boolean isReverse() {
            return newShares.compareTo(oldShares) < 0;
        }

        @Override
        public String describe() {
            return (isReverse() ? "Reverse split " : "Split ")
                    + plain(newShares) + "-for-" + plain(oldShares);
        }
    }

    /**
     * A cash dividend.
     *
     * <p>Adjusting for these is what turns a price return into a total return. Without it, a
     * high-yield stock looks like it drifts down forever - the price gaps down by the dividend on
     * every ex-date and the cash that offsets it is invisible to the chart.
     */
    record CashDividend(InstrumentId instrumentId, LocalDate exDate, LocalDate payDate,
                        Money amountPerShare) implements CorporateAction {

        public CashDividend {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(exDate, "exDate");
            Objects.requireNonNull(payDate, "payDate");
            Objects.requireNonNull(amountPerShare, "amountPerShare");
            if (amountPerShare.isNegative()) {
                throw new IllegalArgumentException("Dividend cannot be negative: " + amountPerShare);
            }
            if (payDate.isBefore(exDate)) {
                throw new IllegalArgumentException(
                        "Pay date " + payDate + " precedes ex-date " + exDate);
            }
        }

        /**
         * The standard proportional adjustment: {@code (close - dividend) / close}.
         *
         * <p>Guards against a zero or fully-consumed close returning a nonsensical factor. A
         * dividend at or above the previous close would produce a factor of zero or less, which
         * would flatten every earlier price to zero - worse than not adjusting at all.
         */
        @Override
        public BigDecimal priceAdjustmentFactor(Price previousClose) {
            if (previousClose.isZero()) {
                return BigDecimal.ONE;
            }
            BigDecimal close = previousClose.value();
            BigDecimal adjusted = close.subtract(amountPerShare.amount());
            if (adjusted.signum() <= 0) {
                return BigDecimal.ONE;
            }
            return adjusted.divide(close, ADJUSTMENT_SCALE, RoundingMode.HALF_EVEN);
        }

        @Override
        public BigDecimal quantityAdjustmentFactor() {
            return BigDecimal.ONE;
        }

        @Override
        public String describe() {
            return "Cash dividend " + amountPerShare.toDisplay().toPlainString() + "/share";
        }
    }

    /**
     * A stock dividend - additional shares issued per share held. Economically a small split, and
     * adjusted the same way: a 5% stock dividend grows the share count by 1.05 and scales
     * historical prices by 1/1.05.
     */
    record StockDividend(InstrumentId instrumentId, LocalDate exDate, BigDecimal sharesPerShare)
            implements CorporateAction {

        public StockDividend {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(exDate, "exDate");
            Objects.requireNonNull(sharesPerShare, "sharesPerShare");
            if (sharesPerShare.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Stock dividend ratio must be positive: " + sharesPerShare);
            }
        }

        private BigDecimal growth() {
            return BigDecimal.ONE.add(sharesPerShare);
        }

        @Override
        public BigDecimal priceAdjustmentFactor(Price previousClose) {
            return BigDecimal.ONE.divide(growth(), ADJUSTMENT_SCALE, RoundingMode.HALF_EVEN);
        }

        @Override
        public BigDecimal quantityAdjustmentFactor() {
            return growth();
        }

        @Override
        public String describe() {
            return "Stock dividend " + plain(sharesPerShare.movePointRight(2)) + "%";
        }
    }

    /**
     * A ticker rename. Neither prices nor share counts move, but the symbol a quote arrives under
     * changes - which is precisely why positions are keyed by {@link InstrumentId} and not by
     * ticker.
     */
    record SymbolChange(InstrumentId instrumentId, LocalDate exDate, String oldSymbol,
                        String newSymbol) implements CorporateAction {

        public SymbolChange {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(exDate, "exDate");
            oldSymbol = Objects.requireNonNull(oldSymbol, "oldSymbol").toUpperCase();
            newSymbol = Objects.requireNonNull(newSymbol, "newSymbol").toUpperCase();
        }

        @Override
        public BigDecimal priceAdjustmentFactor(Price previousClose) {
            return BigDecimal.ONE;
        }

        @Override
        public BigDecimal quantityAdjustmentFactor() {
            return BigDecimal.ONE;
        }

        @Override
        public String describe() {
            return "Symbol change " + oldSymbol + " to " + newSymbol;
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
