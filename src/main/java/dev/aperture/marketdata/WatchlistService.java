package dev.aperture.marketdata;

import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.instrument.SecurityType;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.time.MarketClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the watchlist for each universe, so the grid follows what the account can trade.
 *
 * <p>A futures account has no use for a grid of equities it cannot buy, and an events account has
 * no use for one either. Each universe therefore has its own watchlist, registered into
 * {@link ReferenceDataService} so quotes, charts and search all resolve the same symbols.
 *
 * <h2>Events are configured as series, not symbols</h2>
 *
 * <p>An individual event market is dated - {@code KXFEDDECISION-26SEP-H26} - and ceases to exist
 * once it settles, so a hardcoded list of them empties itself within weeks and the grid quietly
 * goes blank. The stable identifier is the <em>series</em>; the nearest still-tradable market in
 * each is resolved at runtime and refreshed periodically as contracts roll.
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    /** Event contracts settle and roll; re-resolving hourly keeps the grid populated. */
    private static final Duration EVENT_REFRESH_INTERVAL = Duration.ofHours(1);

    private final ApertureProperties.MarketData config;
    private final ReferenceDataService referenceData;
    private final WebullInstrumentClient instrumentClient;
    private final WebullClientHolder holder;
    private final MarketClock clock;

    private final AtomicReference<Instant> eventsResolvedAt = new AtomicReference<>();

    public WatchlistService(ApertureProperties properties, ReferenceDataService referenceData,
                            WebullInstrumentClient instrumentClient, WebullClientHolder holder,
                            MarketClock clock) {
        this.config = properties.marketData();
        this.referenceData = referenceData;
        this.instrumentClient = instrumentClient;
        this.holder = holder;
        this.clock = clock;
        registerStaticWatchlists();
    }

    /**
     * Registers the universes whose symbols are stable enough to configure directly.
     *
     * <p>Equities are already registered by {@link ReferenceDataService} itself. Crypto pairs and
     * futures contract codes do not need the vendor to resolve, so they are registered eagerly;
     * only events require a lookup.
     */
    private void registerStaticWatchlists() {
        register(config.cryptoWatchlist(), SecurityType.CRYPTO, symbol -> symbol);
        register(config.futuresWatchlist(), SecurityType.FUTURE, symbol -> symbol);
    }

    private void register(List<String> symbols, SecurityType type,
                          java.util.function.UnaryOperator<String> nameOf) {
        for (String symbol : symbols) {
            String normalised = symbol == null ? "" : symbol.trim().toUpperCase();
            if (normalised.isEmpty() || referenceData.isKnown(normalised)) {
                continue;
            }
            referenceData.register(new Instrument(
                    InstrumentId.of(normalised), normalised, nameOf.apply(normalised),
                    type, "", "USD", Optional.empty(), true), true);
        }
    }

    /**
     * Resolves each configured event series to its nearest live market and registers it.
     *
     * <p>Costs one vendor request per series, so it is rate-limited by
     * {@link #EVENT_REFRESH_INTERVAL} rather than run on the quote tick.
     */
    public void refreshEventWatchlist() {
        if (!holder.isConnected() || config.eventSeriesWatchlist().isEmpty()) {
            return;
        }
        Instant last = eventsResolvedAt.get();
        if (last != null && last.plus(EVENT_REFRESH_INTERVAL).isAfter(clock.now())) {
            return;
        }
        eventsResolvedAt.set(clock.now());

        var markets = instrumentClient.nearestMarketPerSeries(config.eventSeriesWatchlist());
        if (markets.isEmpty()) {
            return;
        }
        // Drop the previous contracts for these series: once a market settles it should leave the
        // grid rather than linger showing its final price forever.
        for (Instrument existing : referenceData.watchlist(TradableUniverse.EVENT)) {
            referenceData.unwatch(existing.id());
        }
        markets.forEach((series, market) -> {
            String symbol = market.getSymbol().toUpperCase();
            referenceData.register(new Instrument(
                    InstrumentId.of(symbol), symbol,
                    // The market's name is the question being settled, which is the only useful
                    // label - the symbol alone means nothing.
                    market.getName() == null ? symbol : market.getName(),
                    SecurityType.EVENT_CONTRACT, "", "USD", Optional.empty(), true), true);
        });
        log.info("Event watchlist resolved to {} live market(s) from {} series",
                markets.size(), config.eventSeriesWatchlist().size());
    }

    /** The instruments to display for a universe. */
    public List<Instrument> watchlist(TradableUniverse universe) {
        if (universe == TradableUniverse.EVENT) {
            refreshEventWatchlist();
        }
        return referenceData.watchlist(universe);
    }

    /** Whether this universe can be quoted at all on the current entitlement. */
    public boolean isQuotable(TradableUniverse universe) {
        return universe != TradableUniverse.FUTURES;
    }

    /** Why a universe shows no prices, for the grid to state rather than leave blank. */
    public String unquotableReason(TradableUniverse universe) {
        if (universe != TradableUniverse.FUTURES) {
            return "";
        }
        return "Futures market data is a separate Webull entitlement that this account does not "
                + "have. These contracts can be listed and traded, but not quoted or charted here.";
    }
}
