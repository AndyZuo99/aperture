package dev.aperture.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.time.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuoteTest {

    private static final Instant NOW = Instant.parse("2026-09-11T20:00:00Z");

    @Test
    @DisplayName("spread in basis points is relative to the midpoint")
    void spreadBasisPoints() {
        // A one-cent spread is a very different thing on a $10 stock than a $1000 one, which is
        // why the grid shows basis points rather than the raw spread.
        Quote cheap = quote(10.00, 10.01);
        Quote dear = quote(1000.00, 1000.01);
        assertThat(cheap.spreadBasisPoints()).isGreaterThan(dear.spreadBasisPoints());
    }

    @Test
    @DisplayName("a one-sided market has no spread and falls back to the last price")
    void oneSidedMarket() {
        // Common outside regular hours, where a naive midpoint would be half the last price.
        Quote noAsk = quote(10.00, 0);
        assertThat(noAsk.hasTwoSidedMarket()).isFalse();
        assertThat(noAsk.spread().isZero()).isTrue();
        assertThat(noAsk.midpoint()).isEqualTo(noAsk.last());
    }

    @Test
    @DisplayName("a crossed book is not treated as a two-sided market")
    void crossedBook() {
        Quote crossed = quote(10.05, 10.00);
        assertThat(crossed.hasTwoSidedMarket()).isFalse();
    }

    @Test
    @DisplayName("change is measured against the previous close")
    void changeFromPreviousClose() {
        Quote quote = new Quote(InstrumentId.of("AAPL"),
                Price.of(99), Quantity.of(100), Price.of(101), Quantity.of(100),
                Price.of(100), Quantity.of(1000), Price.of(80),
                TradingSession.REGULAR, QuoteProvenance.LIVE_STREAM, NOW, NOW);

        assertThat(quote.changeFromPreviousClose().toDisplay())
                .isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(quote.changePercent()).isEqualByComparingTo(new BigDecimal("25.0000"));
    }

    @Test
    @DisplayName("a zero previous close does not blow up the percentage")
    void zeroPreviousClose() {
        Quote quote = new Quote(InstrumentId.of("AAPL"),
                Price.of(99), Quantity.of(100), Price.of(101), Quantity.of(100),
                Price.of(100), Quantity.of(1000), Price.zero(),
                TradingSession.REGULAR, QuoteProvenance.LIVE_STREAM, NOW, NOW);

        assertThat(quote.changePercent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("provenance decides whether a quote counts as live")
    void provenanceDrivesLiveness() {
        assertThat(QuoteProvenance.LIVE_STREAM.isLive()).isTrue();
        assertThat(QuoteProvenance.LIVE_REST.isLive()).isTrue();
        assertThat(QuoteProvenance.SIMULATED.isLive()).isFalse();
    }

    private static Quote quote(double bid, double ask) {
        return new Quote(InstrumentId.of("TEST"),
                Price.of(bid), Quantity.of(100), Price.of(ask), Quantity.of(100),
                Price.of(bid), Quantity.of(1000), Price.of(bid),
                TradingSession.REGULAR, QuoteProvenance.LIVE_STREAM, NOW, NOW);
    }
}
