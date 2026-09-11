package dev.aperture.marketdata;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.time.TradingSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A Level 1 quote: top of book plus the last print.
 *
 * <p>Assembled from two separate Webull streaming messages. {@code SNAPSHOT} carries last, open,
 * high, low, previous close and volume but <em>no bid or ask</em>; {@code QUOTE} carries the BBO
 * with sizes but <em>no last price</em>. Subscribing to one alone yields a quote that looks
 * complete and is half empty - see {@code WebullStreamingQuoteSource.Partial}.
 *
 * @param eventTime when the venue says this happened
 * @param receivedAt when Aperture saw it; the two differ by the wire latency, and staleness is
 *     measured against this one
 */
public record Quote(
        InstrumentId instrumentId,
        Price bid,
        Quantity bidSize,
        Price ask,
        Quantity askSize,
        Price last,
        Quantity volume,
        Price previousClose,
        TradingSession session,
        QuoteProvenance provenance,
        Instant eventTime,
        Instant receivedAt) {

    public Quote {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(bidSize, "bidSize");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(askSize, "askSize");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(previousClose, "previousClose");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** Whether a two-sided market is actually quoted. Outside regular hours it often is not. */
    public boolean hasTwoSidedMarket() {
        return bid.isPositive() && ask.isPositive() && ask.isGreaterThan(bid);
    }

    public Price spread() {
        return hasTwoSidedMarket() ? ask.minus(bid) : Price.zero();
    }

    /** Spread in basis points of the midpoint - the comparable measure across price levels. */
    public BigDecimal spreadBasisPoints() {
        if (!hasTwoSidedMarket()) {
            return BigDecimal.ZERO;
        }
        BigDecimal mid = midpoint().value();
        if (mid.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return spread().value()
                .multiply(BigDecimal.valueOf(10_000))
                .divide(mid, 2, RoundingMode.HALF_EVEN);
    }

    /** Midpoint when two-sided, otherwise the last print. */
    public Price midpoint() {
        if (!hasTwoSidedMarket()) {
            return last;
        }
        return Price.of(bid.value().add(ask.value())
                .divide(BigDecimal.valueOf(2), Price.SCALE, RoundingMode.HALF_EVEN));
    }

    public Money changeFromPreviousClose() {
        return Money.usd(last.value().subtract(previousClose.value()));
    }

    public BigDecimal changePercent() {
        if (previousClose.isZero()) {
            return BigDecimal.ZERO;
        }
        return last.value().subtract(previousClose.value())
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose.value(), 4, RoundingMode.HALF_EVEN);
    }

    public Duration age(Instant now) {
        return Duration.between(receivedAt, now);
    }

    public boolean isLive() {
        return provenance.isLive();
    }
}
