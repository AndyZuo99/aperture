package dev.aperture.analysis;

import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.PriceHistory;
import dev.aperture.marketdata.WebullInstrumentClient;
import dev.aperture.time.MarketClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Daily bars for any instrument in any universe, whether or not it is on the watchlist.
 *
 * <p>Watchlist equities are already backfilled and split-adjusted in {@link PriceHistory}, so
 * those are served from there. Anything else - a crypto pair, an event contract, an equity outside
 * the watchlist - is fetched on demand and cached, because the recommender needs to look at
 * instruments nobody has charted yet.
 */
@Service
public class HistoryProvider {

    /** Enough to compute a stable one-month hit rate without pulling decades. */
    public static final int DEFAULT_SESSIONS = 250;

    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final PriceHistory priceHistory;
    private final ReferenceDataService referenceData;
    private final WebullInstrumentClient instrumentClient;
    private final MarketClock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public HistoryProvider(PriceHistory priceHistory, ReferenceDataService referenceData,
                           WebullInstrumentClient instrumentClient, MarketClock clock) {
        this.priceHistory = priceHistory;
        this.referenceData = referenceData;
        this.instrumentClient = instrumentClient;
        this.clock = clock;
    }

    /** Adjusted daily bars, or empty when the vendor has no history for this instrument. */
    public List<Bar> bars(String symbol, TradableUniverse universe) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String key = universe.name() + ":" + symbol.toUpperCase();

        if (universe == TradableUniverse.EQUITY) {
            // Already loaded and split-adjusted for watchlist names.
            var instrument = referenceData.resolve(symbol);
            if (instrument.isPresent() && priceHistory.hasHistory(instrument.get().id())) {
                return priceHistory.bars(instrument.get().id(), AdjustmentPolicy.SPLITS_ONLY);
            }
        }

        Cached cached = cache.get(key);
        if (cached != null && cached.isFresh(clock.now())) {
            return cached.bars();
        }
        List<Bar> fetched = instrumentClient.history(symbol, universe, DEFAULT_SESSIONS);
        cache.put(key, new Cached(fetched, clock.now()));
        return fetched;
    }

    /** Whether history exists at all - false for futures, which need a separate entitlement. */
    public boolean hasHistory(String symbol, TradableUniverse universe) {
        return !bars(symbol, universe).isEmpty();
    }

    private record Cached(List<Bar> bars, Instant fetchedAt) {
        boolean isFresh(Instant now) {
            return fetchedAt.plus(CACHE_TTL).isAfter(now);
        }
    }
}
