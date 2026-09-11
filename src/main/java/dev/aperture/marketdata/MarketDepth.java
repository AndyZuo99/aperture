package dev.aperture.marketdata;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.InstrumentId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The order book as far as the entitlement allows.
 *
 * <p>Under Nasdaq Basic that is exactly one level per side - the touch is real and everything
 * behind it is invisible. That is a meaningfully different thing from an empty book, and the UI
 * says which it is: real depth is labelled as observed, and the absence of depth is labelled as
 * not entitled rather than as no liquidity.
 */
public record MarketDepth(
        InstrumentId instrumentId,
        List<Level> bids,
        List<Level> asks,
        Instant eventTime,
        Instant receivedAt) {

    public MarketDepth {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    /** How many levels came back, which is what the entitlement actually permits. */
    public int levelCount() {
        return Math.max(bids.size(), asks.size());
    }

    public Optional<Level> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.get(0));
    }

    public Optional<Level> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.get(0));
    }

    /** One price level: everyone resting at that price, aggregated. */
    public record Level(Price price, Quantity size) {
        public Level {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(size, "size");
        }
    }
}
