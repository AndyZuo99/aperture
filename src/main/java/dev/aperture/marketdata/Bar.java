package dev.aperture.marketdata;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.InstrumentId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One daily OHLCV bar.
 *
 * <p>{@code basis} records what these prices have already been restated for. It is a
 * {@link PriceBasis} rather than a boolean because "adjusted" is not a yes-or-no property:
 * Webull's bars arrive split-adjusted but not dividend-adjusted, and a series that does not know
 * that gets adjusted for splits a second time. Carrying the basis on the data is what makes that
 * mistake unrepresentable.
 */
public record Bar(
        InstrumentId instrumentId,
        LocalDate sessionDate,
        /**
         * When this bar opened.
         *
         * <p>Carried because a bar is not always a day: the one-week chart and the live session
         * view use thirty- and one-minute bars, and every bar in a session shares a session date.
         * Without a time they would all plot at the same x position.
         */
        Instant startTime,
        Price open,
        Price high,
        Price low,
        Price close,
        Quantity volume,
        PriceBasis basis) {

    public Bar {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(sessionDate, "sessionDate");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(basis, "basis");
    }

    /**
     * A daily bar, whose start is taken as the session's regular open.
     *
     * <p>Most of the system deals in daily bars, so they should not have to state a time that is
     * implied by the date.
     */
    public Bar(InstrumentId instrumentId, LocalDate sessionDate, Price open, Price high,
               Price low, Price close, Quantity volume, PriceBasis basis) {
        this(instrumentId, sessionDate,
                sessionDate.atTime(dev.aperture.time.MarketCalendar.REGULAR_OPEN)
                        .atZone(dev.aperture.time.MarketCalendar.EXCHANGE_ZONE).toInstant(),
                open, high, low, close, volume, basis);
    }

    /** Whether this bar covers less than a full session. */
    public boolean isIntraday() {
        return !startTime.equals(sessionDate.atTime(dev.aperture.time.MarketCalendar.REGULAR_OPEN)
                .atZone(dev.aperture.time.MarketCalendar.EXCHANGE_ZONE).toInstant());
    }

    /**
     * Restates this bar and records the basis it is now on.
     *
     * @param priceFactor multiplies every price
     * @param volumeFactor multiplies the volume - the reciprocal of the price factor for a split,
     *     since ten times as many shares trade at a tenth of the price, and one for a dividend
     * @param newBasis what the result is now adjusted for
     */
    public Bar scaled(BigDecimal priceFactor, BigDecimal volumeFactor, PriceBasis newBasis) {
        if (priceFactor.compareTo(BigDecimal.ONE) == 0
                && volumeFactor.compareTo(BigDecimal.ONE) == 0) {
            return basis == newBasis ? this : withBasis(newBasis);
        }
        return new Bar(
                instrumentId,
                sessionDate,
                startTime,
                open.scaledBy(priceFactor),
                high.scaledBy(priceFactor),
                low.scaledBy(priceFactor),
                close.scaledBy(priceFactor),
                volume.scaledBy(volumeFactor),
                newBasis);
    }

    public Bar withBasis(PriceBasis newBasis) {
        return new Bar(instrumentId, sessionDate, startTime, open, high, low, close, volume,
                newBasis);
    }

    /** Simple return from the previous bar's close, as a fraction. */
    public BigDecimal returnFrom(Bar previous) {
        if (previous.close.isZero()) {
            return BigDecimal.ZERO;
        }
        return close.value()
                .subtract(previous.close.value())
                .divide(previous.close.value(), 8, RoundingMode.HALF_EVEN);
    }

    public BigDecimal range() {
        return high.value().subtract(low.value());
    }
}
