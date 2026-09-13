package dev.aperture.instrument;

import dev.aperture.config.ApertureProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * The instrument registry: the mapping from tickers to stable {@link InstrumentId}s.
 *
 * <p>Seeded from the configured watchlist at startup and extended at runtime when a symbol is
 * adopted. Symbols map onto ids, never the reverse, so a ticker rename updates one mapping
 * instead of rewriting every position and order that referenced it.
 */
@Service
public class ReferenceDataService {

    private final Map<InstrumentId, Instrument> byId = new ConcurrentHashMap<>();
    private final Map<String, InstrumentId> bySymbol = new ConcurrentHashMap<>();

    /**
     * The subset that is actively quoted, streamed and charted.
     *
     * <p>Everything else in the registry is a <em>candidate</em>: backfilled with daily bars so
     * the recommender can scan it, but never subscribed to. A few hundred candidates cost a
     * handful of bar requests a day; subscribing to them would cost a few hundred streaming
     * subscriptions and swamp the quote grid.
     */
    private final Set<InstrumentId> watched = ConcurrentHashMap.newKeySet();

    public ReferenceDataService(ApertureProperties properties) {
        for (String symbol : properties.marketData().watchlist()) {
            registerSymbol(symbol, true);
        }
        for (String symbol : properties.marketData().candidateUniverse()) {
            registerSymbol(symbol, false);
        }
    }

    private void registerSymbol(String symbol, boolean watch) {
        String normalised = symbol == null ? "" : symbol.trim().toUpperCase();
        if (normalised.isEmpty() || bySymbol.containsKey(normalised)) {
            return;
        }
        register(new Instrument(
                InstrumentId.of(normalised),
                normalised,
                nameFor(normalised),
                typeFor(normalised),
                "NASDAQ",
                "USD",
                Optional.empty(),
                true), watch);
    }

    /** Registers an instrument as watched. */
    public Instrument register(Instrument instrument) {
        return register(instrument, true);
    }

    public Instrument register(Instrument instrument, boolean watch) {
        byId.put(instrument.id(), instrument);
        bySymbol.put(instrument.primarySymbol(), instrument.id());
        if (watch) {
            watched.add(instrument.id());
        }
        return instrument;
    }

    public Optional<Instrument> byId(InstrumentId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Resolves a ticker to an instrument, case-insensitively. */
    public Optional<Instrument> resolve(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        InstrumentId id = bySymbol.get(symbol.trim().toUpperCase());
        return id == null ? Optional.empty() : byId(id);
    }

    public boolean isKnown(String symbol) {
        return resolve(symbol).isPresent();
    }

    /** Every instrument, in a stable order so the UI does not reshuffle between refreshes. */
    public List<Instrument> all() {
        return byId.values().stream()
                .sorted(java.util.Comparator.comparing(Instrument::primarySymbol))
                .toList();
    }

    /** Everything tradable, watched or not. Used for history backfill and candidate scanning. */
    public List<Instrument> tradable() {
        return all().stream().filter(Instrument::isTradable).toList();
    }

    /**
     * Tradable instruments in one universe.
     *
     * <p>The equity backfill and the candidate scan both use this: issuing
     * {@code getBatchBars} for a crypto pair is not merely wasteful, it fails every cycle and the
     * instrument is never marked complete, so the scheduler retries it forever.
     */
    public List<Instrument> tradable(TradableUniverse universe) {
        return tradable().stream()
                .filter(instrument -> instrument.type().universe() == universe)
                .toList();
    }

    /** Listed equities and funds - the only instruments the equity bar endpoints accept. */
    public List<Instrument> equities() {
        return tradable(TradableUniverse.EQUITY);
    }

    /** Only the instruments to quote, stream and chart. */
    public List<Instrument> watchlist() {
        return all().stream()
                .filter(Instrument::isTradable)
                .filter(instrument -> watched.contains(instrument.id()))
                .toList();
    }

    /** The watched instruments of one universe - what that account's grid shows. */
    public List<Instrument> watchlist(TradableUniverse universe) {
        return watchlist().stream()
                .filter(instrument -> instrument.type().universe() == universe)
                .toList();
    }

    public boolean isWatched(InstrumentId id) {
        return watched.contains(id);
    }

    /**
     * Stops watching an instrument without forgetting it.
     *
     * <p>Used when an event contract settles and its series rolls to the next one: the old market
     * should leave the grid, but anything already referring to it must still resolve.
     */
    public void unwatch(InstrumentId id) {
        watched.remove(id);
    }

    public int candidateCount() {
        return (int) all().stream()
                .filter(instrument -> !watched.contains(instrument.id()))
                .count();
    }

    public Collection<InstrumentId> allIds() {
        return List.copyOf(byId.keySet());
    }

    /**
     * Records a ticker rename, keeping the instrument id stable.
     *
     * <p>The old symbol stops resolving, which is deliberate: after META, a lookup of "FB" should
     * fail loudly rather than quietly return a different company's data.
     */
    public Optional<Instrument> rename(InstrumentId id, String newSymbol) {
        return byId(id).map(existing -> {
            bySymbol.remove(existing.primarySymbol());
            Instrument renamed = existing.renamedTo(newSymbol.toUpperCase());
            return register(renamed);
        });
    }

    public Map<String, Instrument> bySymbolMap() {
        Map<String, Instrument> out = new LinkedHashMap<>();
        for (Instrument instrument : all()) {
            out.put(instrument.primarySymbol(), instrument);
        }
        return out;
    }

    /**
     * Display names for the default watchlist. Webull's own {@code getCompanyProfile} is
     * authoritative and is used when the feed is live; this only has to be reasonable offline.
     */
    private static String nameFor(String symbol) {
        return switch (symbol) {
            case "AAPL" -> "Apple Inc.";
            case "MSFT" -> "Microsoft Corporation";
            case "NVDA" -> "NVIDIA Corporation";
            case "AMZN" -> "Amazon.com, Inc.";
            case "GOOGL" -> "Alphabet Inc. Class A";
            case "META" -> "Meta Platforms, Inc.";
            case "TSLA" -> "Tesla, Inc.";
            case "JPM" -> "JPMorgan Chase & Co.";
            case "V" -> "Visa Inc.";
            case "SPY" -> "SPDR S&P 500 ETF Trust";
            case "QQQ" -> "Invesco QQQ Trust";
            case "IWM" -> "iShares Russell 2000 ETF";
            default -> symbol;
        };
    }

    private static SecurityType typeFor(String symbol) {
        return switch (symbol) {
            case "SPY", "QQQ", "IWM", "VTI", "VOO" -> SecurityType.ETF;
            default -> SecurityType.COMMON_STOCK;
        };
    }
}
