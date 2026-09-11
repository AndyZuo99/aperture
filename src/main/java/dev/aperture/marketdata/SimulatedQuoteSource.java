package dev.aperture.marketdata;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A local price simulator, used when Webull is unreachable, unconfigured, or not entitled.
 *
 * <p>This exists so the application is runnable by someone who has cloned it and has no
 * credentials - the difference between a reviewer seeing a working console and seeing a stack
 * trace. Every quote it produces is labelled {@link QuoteProvenance#SIMULATED} all the way to the
 * UI, which shows a persistent banner while it is in use. It is never allowed to look live.
 *
 * <p>Prices follow a geometric random walk with a per-symbol volatility, which is enough to make
 * the chart, the spread display and the blotter behave realistically without pretending to model
 * anything.
 */
@Component
public class SimulatedQuoteSource implements QuoteSource {

    private static final BigDecimal ANNUAL_TRADING_DAYS = BigDecimal.valueOf(252);

    private final ReferenceDataService referenceData;
    private final MarketClock clock;
    private final Map<InstrumentId, State> states = new ConcurrentHashMap<>();
    private final Random random = new Random(20260911L);

    public SimulatedQuoteSource(ReferenceDataService referenceData, MarketClock clock) {
        this.referenceData = referenceData;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "SIMULATED";
    }

    @Override
    public QuoteProvenance provenance() {
        return QuoteProvenance.SIMULATED;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<Quote> latest(InstrumentId instrumentId) {
        return referenceData.byId(instrumentId).map(this::quoteFor);
    }

    @Override
    public Map<InstrumentId, Quote> snapshot(Set<InstrumentId> instrumentIds) {
        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        for (InstrumentId id : instrumentIds) {
            referenceData.byId(id).ifPresent(instrument -> out.put(id, quoteFor(instrument)));
        }
        return out;
    }

    /** Advances every simulated price by one tick. Driven by the scheduler. */
    public void tick() {
        for (Instrument instrument : referenceData.watchlist()) {
            state(instrument).advance(random);
        }
    }

    /**
     * Seeds a symbol's simulated price from a real one, so that when the live feed is available
     * at startup but drops later, the simulator continues from where the market actually was
     * instead of snapping back to an invented number.
     */
    public void seed(InstrumentId instrumentId, Price price) {
        if (price.isPositive()) {
            state(instrumentId, price.value()).reset(price.value());
        }
    }

    private Quote quoteFor(Instrument instrument) {
        State state = state(instrument);
        Instant now = clock.now();
        BigDecimal last = state.price;
        // Spread widens outside regular hours, as it does in reality.
        BigDecimal halfSpread = last.multiply(clock.isRegularHours()
                        ? BigDecimal.valueOf(0.00015) : BigDecimal.valueOf(0.0009))
                .setScale(Price.SCALE, RoundingMode.HALF_EVEN);
        return new Quote(
                instrument.id(),
                Price.of(last.subtract(halfSpread)),
                Quantity.of(100L * (1 + random.nextInt(20))),
                Price.of(last.add(halfSpread)),
                Quantity.of(100L * (1 + random.nextInt(20))),
                Price.of(last),
                Quantity.of(state.volume),
                Price.of(state.previousClose),
                clock.currentSession(),
                QuoteProvenance.SIMULATED,
                now,
                now);
    }

    private State state(Instrument instrument) {
        return state(instrument.id(), seedPriceFor(instrument.primarySymbol()));
    }

    private State state(InstrumentId id, BigDecimal seedPrice) {
        return states.computeIfAbsent(id, ignored -> new State(seedPrice, volatilityFor(id)));
    }

    /**
     * Plausible starting prices. Only used before any real quote arrives, so being approximately
     * right is enough - and being obviously synthetic is fine, because the provenance label says
     * so.
     */
    private static BigDecimal seedPriceFor(String symbol) {
        return switch (symbol) {
            case "AAPL" -> BigDecimal.valueOf(326.57);
            case "MSFT" -> BigDecimal.valueOf(512.40);
            case "NVDA" -> BigDecimal.valueOf(218.36);
            case "AMZN" -> BigDecimal.valueOf(241.80);
            case "GOOGL" -> BigDecimal.valueOf(198.25);
            case "META" -> BigDecimal.valueOf(742.10);
            case "TSLA" -> BigDecimal.valueOf(408.90);
            case "JPM" -> BigDecimal.valueOf(298.15);
            case "V" -> BigDecimal.valueOf(352.70);
            case "SPY" -> BigDecimal.valueOf(682.30);
            default -> BigDecimal.valueOf(100);
        };
    }

    private static BigDecimal volatilityFor(InstrumentId id) {
        return switch (id.value()) {
            case "NVDA", "TSLA" -> BigDecimal.valueOf(0.55);
            case "META", "AMZN" -> BigDecimal.valueOf(0.38);
            case "SPY" -> BigDecimal.valueOf(0.16);
            default -> BigDecimal.valueOf(0.28);
        };
    }

    /** One symbol's simulated state. */
    private static final class State {
        private BigDecimal price;
        private BigDecimal previousClose;
        private BigDecimal volume;
        private final BigDecimal dailyVolatility;

        State(BigDecimal seedPrice, BigDecimal annualVolatility) {
            this.price = seedPrice;
            this.previousClose = seedPrice;
            this.volume = BigDecimal.valueOf(1_000_000);
            // Annualised volatility is how it is always quoted; the per-tick move needs the daily
            // figure. Forgetting the sqrt(252) here overstates every move by about 16x.
            this.dailyVolatility = annualVolatility.divide(
                    BigDecimal.valueOf(Math.sqrt(ANNUAL_TRADING_DAYS.doubleValue())),
                    8, RoundingMode.HALF_EVEN);
        }

        void advance(Random random) {
            // One tick is a fraction of a session, so scale the daily move down accordingly.
            double shock = random.nextGaussian() * dailyVolatility.doubleValue() / 40.0;
            BigDecimal next = price.multiply(BigDecimal.valueOf(1 + shock))
                    .setScale(Price.SCALE, RoundingMode.HALF_EVEN);
            if (next.signum() > 0) {
                price = next;
            }
            volume = volume.add(BigDecimal.valueOf(100L * (1 + random.nextInt(50))));
        }

        void reset(BigDecimal newPrice) {
            this.previousClose = this.price;
            this.price = newPrice;
        }
    }
}
