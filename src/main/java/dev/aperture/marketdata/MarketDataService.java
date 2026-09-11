package dev.aperture.marketdata;

import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.time.MarketClock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single place the rest of the application asks for a quote.
 *
 * <h2>Source precedence</h2>
 *
 * <p>Streaming, then REST polling, then the simulator - each falling through only when the one
 * above is genuinely unavailable. Callers never choose; they get the best available and the
 * {@link QuoteProvenance} that says which it was, so no caller can accidentally treat a simulated
 * price as live.
 *
 * <p>Quotes are cached per instrument and only replaced by a strictly better one, so a stale REST
 * poll cannot overwrite a fresher streaming tick that arrived a moment earlier.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final ReferenceDataService referenceData;
    private final WebullStreamingQuoteSource streaming;
    private final WebullQuoteClient rest;
    private final SimulatedQuoteSource simulated;
    private final WebullClientHolder holder;
    private final MarketClock clock;
    private final ApertureProperties.MarketData config;
    private final ApertureProperties.Webull webullConfig;

    private final Map<InstrumentId, Quote> latest = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Quote>> listeners = new CopyOnWriteArrayList<>();
    private volatile Instant lastUpdateAt;

    public MarketDataService(ReferenceDataService referenceData,
                             WebullStreamingQuoteSource streaming,
                             WebullQuoteClient rest,
                             SimulatedQuoteSource simulated,
                             WebullClientHolder holder,
                             MarketClock clock,
                             ApertureProperties properties) {
        this.referenceData = referenceData;
        this.streaming = streaming;
        this.rest = rest;
        this.simulated = simulated;
        this.holder = holder;
        this.clock = clock;
        this.config = properties.marketData();
        this.webullConfig = properties.webull();

        // Streaming pushes rather than being polled, so its quotes arrive through this callback.
        streaming.onQuote(this::accept);
    }

    /** Registers a listener notified on every quote change. The WebSocket handler uses this. */
    public void onQuote(Consumer<Quote> listener) {
        listeners.add(listener);
    }

    public Optional<Quote> quote(InstrumentId instrumentId) {
        return Optional.ofNullable(latest.get(instrumentId));
    }

    public Optional<Quote> quote(String symbol) {
        return referenceData.resolve(symbol).flatMap(i -> quote(i.id()));
    }

    /** Every current quote, in watchlist order. */
    public Map<InstrumentId, Quote> allQuotes() {
        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        for (Instrument instrument : referenceData.all()) {
            Quote quote = latest.get(instrument.id());
            if (quote != null) {
                out.put(instrument.id(), quote);
            }
        }
        return out;
    }

    public Map<InstrumentId, Quote> quotes(Set<InstrumentId> instrumentIds) {
        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        for (InstrumentId id : instrumentIds) {
            Quote quote = latest.get(id);
            if (quote != null) {
                out.put(id, quote);
            }
        }
        return out;
    }

    /**
     * Pulls a fresh round of quotes. Called by the scheduler.
     *
     * <p>Streaming instruments are skipped: they are already being pushed, and re-polling them
     * would spend request budget to produce a staler answer.
     */
    public void poll() {
        List<Instrument> instruments = referenceData.tradable();
        if (instruments.isEmpty()) {
            return;
        }

        Set<InstrumentId> streamed = streaming.isAvailable()
                ? streaming.coveredInstruments() : Set.of();
        List<Instrument> needingPoll = instruments.stream()
                .filter(i -> !streamed.contains(i.id()))
                .toList();

        if (!needingPoll.isEmpty() && holder.isConnected() && !rest.isEntitlementMissing()) {
            rest.quotes(needingPoll).values().forEach(this::accept);
        }

        // Anything still without a live quote falls back to the simulator, so the UI is never
        // blank. The provenance label carries the distinction.
        simulated.tick();
        for (Instrument instrument : instruments) {
            Quote existing = latest.get(instrument.id());
            if (existing == null || shouldFallBack(existing)) {
                simulated.latest(instrument.id()).ifPresent(this::accept);
            }
        }
    }

    /**
     * Whether a live quote has gone stale enough to fall back to the simulator.
     *
     * <p>Only applies while the market is open. Outside trading hours a quote from the close is
     * the correct thing to show, and replacing it with a simulated tick would be actively worse.
     */
    private boolean shouldFallBack(Quote existing) {
        if (!existing.isLive()) {
            return true;
        }
        if (!clock.isOpen()) {
            return false;
        }
        return config.isStale(existing.receivedAt(), clock.now());
    }

    /**
     * Accepts a quote from any source, keeping the better of it and what is already held.
     *
     * <p>"Better" is live-over-simulated first, then more recent. Without the first rule a
     * simulated fallback tick would immediately overwrite the live quote it was standing in for.
     */
    private void accept(Quote quote) {
        latest.merge(quote.instrumentId(), quote, MarketDataService::preferred);
        Quote current = latest.get(quote.instrumentId());
        if (current != quote) {
            return;
        }
        lastUpdateAt = quote.receivedAt();
        if (quote.isLive()) {
            // Keep the simulator anchored to reality, so a later feed outage continues from the
            // real price rather than snapping to an invented one.
            simulated.seed(quote.instrumentId(), quote.last());
        }
        for (Consumer<Quote> listener : listeners) {
            try {
                listener.accept(quote);
            } catch (RuntimeException e) {
                log.debug("Quote listener threw: {}", e.getMessage());
            }
        }
    }

    private static Quote preferred(Quote existing, Quote candidate) {
        if (existing.isLive() != candidate.isLive()) {
            return existing.isLive() ? existing : candidate;
        }
        return candidate.receivedAt().isBefore(existing.receivedAt()) ? existing : candidate;
    }

    /** Best bid and offer with sizes, when the entitlement permits it. */
    public Optional<MarketDepth> depth(InstrumentId instrumentId, int levels) {
        return referenceData.byId(instrumentId).flatMap(i -> rest.depth(i, levels));
    }

    /** The current feed state, for the UI status bar. */
    public FeedStatus status() {
        QuoteSource active = activeSource();
        Instant lastUpdate = lastUpdateAt;
        boolean receiving = lastUpdate != null
                && active.provenance() != QuoteProvenance.SIMULATED;
        return new FeedStatus(
                active.sourceName(),
                active.provenance(),
                webullConfig.hasCredentials(),
                holder.isConnected(),
                receiving,
                streaming.status().name(),
                Optional.ofNullable(lastUpdate),
                streaming.detail().or(rest::lastError).or(holder::detail),
                rest.permittedDepthLevels());
    }

    private QuoteSource activeSource() {
        if (streaming.isAvailable()) {
            return streaming;
        }
        if (rest.isAvailable()) {
            return rest;
        }
        return simulated;
    }

    public WebullStreamingQuoteSource streamingSource() {
        return streaming;
    }

    public WebullQuoteClient restSource() {
        return rest;
    }
}
