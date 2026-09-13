package dev.aperture.marketdata;

import com.webull.openapi.core.common.dict.Category;
import com.webull.openapi.core.common.dict.SubscribeType;
import com.webull.openapi.data.quotes.domain.AskBid;
import com.webull.openapi.data.quotes.domain.QuotesBasic;
import com.webull.openapi.data.quotes.domain.Snapshot;
import com.webull.openapi.data.quotes.subscribe.IDataStreamingClient;
import com.webull.openapi.data.quotes.subscribe.message.MarketData;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.time.MarketClock;
import dev.aperture.time.TradingSession;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live Level 1 quotes pushed over Webull's MQTT feed.
 *
 * <h2>A quote is two messages, not one</h2>
 *
 * <p>The feed splits Level 1 across two subscribe types that must be merged:
 * <ul>
 *   <li>{@code SNAPSHOT} carries last price, open, high, low, previous close and volume - and
 *       <strong>no bid or ask</strong>.
 *   <li>{@code QUOTE} carries the best bid and offer with sizes - and <strong>no last
 *       price</strong>.
 * </ul>
 * Subscribing to only one produces a quote that looks perfectly well-formed and is half empty:
 * either a market with no trade, or a trade with no market. {@link Partial} accumulates both and
 * only emits once a last price exists.
 *
 * <h2>One bad symbol kills the whole subscription</h2>
 *
 * <p>The feed rejects the <em>entire</em> subscribe request with {@code 417 INVALID_SYMBOL} if any
 * single symbol is unknown, rather than skipping it. Symbols are therefore validated through the
 * free {@code getInstruments} lookup first - otherwise one typo in a watchlist takes the whole
 * feed down.
 */
@Component
public class WebullStreamingQuoteSource implements QuoteSource {

    private static final Logger log = LoggerFactory.getLogger(WebullStreamingQuoteSource.class);

    private static final Set<String> SUBSCRIBE_TYPES =
            Set.of(SubscribeType.QUOTE.name(), SubscribeType.SNAPSHOT.name());

    private final ReferenceDataService referenceData;
    private final WebullQuoteClient restClient;
    private final WebullClientHolder holder;
    private final MarketClock clock;
    private final ApertureProperties.Webull webullConfig;
    private final ApertureProperties.MarketData marketDataConfig;

    private final Map<InstrumentId, Quote> latest = new ConcurrentHashMap<>();
    private final Map<InstrumentId, Partial> partials = new ConcurrentHashMap<>();
    private final Map<String, InstrumentId> bySymbol = new ConcurrentHashMap<>();
    private final Set<InstrumentId> covered = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<Quote>> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_CONFIGURED);
    private final AtomicReference<String> detail = new AtomicReference<>();
    private final AtomicReference<Instant> lastMessage = new AtomicReference<>();
    private final AtomicReference<Instant> lastSubscribeAttempt = new AtomicReference<>();

    private volatile IDataStreamingClient client;
    private ExecutorService executor;

    public WebullStreamingQuoteSource(ReferenceDataService referenceData,
                                      WebullQuoteClient restClient,
                                      WebullClientHolder holder,
                                      MarketClock clock,
                                      ApertureProperties properties) {
        this.referenceData = referenceData;
        this.restClient = restClient;
        this.holder = holder;
        this.clock = clock;
        this.webullConfig = properties.webull();
        this.marketDataConfig = properties.marketData();
    }

    @Override
    public String sourceName() {
        return "WEBULL_STREAM";
    }

    @Override
    public QuoteProvenance provenance() {
        return QuoteProvenance.LIVE_STREAM;
    }

    @Override
    public boolean isAvailable() {
        return status.get() == Status.STREAMING;
    }

    @Override
    public Optional<Quote> latest(InstrumentId instrumentId) {
        return Optional.ofNullable(latest.get(instrumentId));
    }

    @Override
    public Map<InstrumentId, Quote> snapshot(Set<InstrumentId> instrumentIds) {
        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        for (InstrumentId id : instrumentIds) {
            Quote quote = latest.get(id);
            if (quote != null) {
                out.put(id, quote);
            }
        }
        return out;
    }

    /** Registers a listener called on every merged quote. Used to push over the WebSocket. */
    public void onQuote(Consumer<Quote> listener) {
        listeners.add(listener);
    }

    public Status status() {
        return status.get();
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail.get());
    }

    public Optional<Instant> lastMessageAt() {
        return Optional.ofNullable(lastMessage.get());
    }

    public Set<InstrumentId> coveredInstruments() {
        return Set.copyOf(covered);
    }

    /** Starts the feed on a daemon thread. Safe to call when unconfigured; it simply no-ops. */
    public void start() {
        if (!webullConfig.isUsable() || !marketDataConfig.streamingEnabled()) {
            detail.set(webullConfig.isUsable()
                    ? "Streaming disabled by configuration"
                    : "No Webull credentials, or the integration is disabled");
            return;
        }
        if (executor != null) {
            return;
        }
        status.set(Status.CONNECTING);
        detail.set("Connecting to the Webull streaming feed");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "webull-stream");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::connectAndSubscribe);
    }

    private void connectAndSubscribe() {
        Set<String> candidates = equitySymbols();
        if (candidates.isEmpty()) {
            status.set(Status.FAILED);
            detail.set("No instruments to subscribe to");
            return;
        }
        if (!holder.isConnected()) {
            // The REST handshake has not finished yet. Leave the status at CONNECTING; the
            // scheduler's retry will come back once the client exists.
            status.set(Status.CONNECTING);
            detail.set("Waiting for the Webull handshake before subscribing");
            return;
        }

        Set<String> symbols = restClient.knownSymbols(candidates, Category.US_STOCK);
        if (symbols.isEmpty()) {
            status.set(Status.FAILED);
            detail.set("None of the " + candidates.size() + " symbols are listed by the vendor");
            return;
        }
        if (symbols.size() < candidates.size()) {
            Set<String> skipped = new LinkedHashSet<>(candidates);
            skipped.removeAll(symbols);
            log.info("Skipping {} symbol(s) the vendor does not list: {}", skipped.size(), skipped);
        }

        try {
            client = IDataStreamingClient.builder()
                    .appKey(webullConfig.appKey())
                    .appSecret(webullConfig.appSecret())
                    .regionId(webullConfig.regionId())
                    // Required: without a session id the builder throws "sid is blank". Any
                    // unique value works, so a fresh UUID per connection.
                    .sessionId(UUID.randomUUID().toString())
                    .onMessage(this::onMarketData)
                    .onNotice(notice -> log.debug("Webull stream notice: {}", notice))
                    .build();
            client.connectBlocking();
            status.set(Status.CONNECTED);
            lastSubscribeAttempt.set(clock.now());
            log.info("Webull streaming feed connected; subscribing to {} symbols", symbols.size());

            try {
                client.addSubscriptionBlocking(
                        symbols, Category.US_STOCK.name(), SUBSCRIBE_TYPES, null, Boolean.FALSE);
            } catch (RuntimeException e) {
                if (indicatesMissingSubscription(e)) {
                    status.set(Status.NOT_SUBSCRIBED);
                    detail.set("Connected, but the account's OpenAPI market-data subscription "
                            + "does not cover streaming quotes");
                    log.warn("Webull streaming connected and the subscription was refused. The "
                            + "OpenAPI market-data subscription is separate from the Webull app's "
                            + "and must be claimed in the developer portal under Advanced Quotes "
                            + "-> OpenAPI Advanced Quotes. Aperture will fall back to REST polling.");
                    closeQuietly();
                    return;
                }
                throw e;
            }

            covered.clear();
            for (String symbol : symbols) {
                InstrumentId id = bySymbol.get(symbol.toUpperCase());
                if (id != null) {
                    covered.add(id);
                }
            }
            status.set(Status.STREAMING);
            detail.set("Streaming Level 1 for " + symbols.size() + " symbols");
            log.info("Webull Level 1 streaming feed is live for {} symbols", symbols.size());
            client.subscribeBlocking();
        } catch (Throwable t) {
            status.set(Status.FAILED);
            detail.set(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            log.warn("Webull streaming feed unavailable ({}); falling back to REST polling",
                    detail.get());
            closeQuietly();
        }
    }

    /**
     * Retries a connection that failed or was refused.
     *
     * <p>Worth retrying because both causes are recoverable without a restart: the REST handshake
     * may simply not have finished yet, and a subscription activated in the portal takes effect
     * on the next attempt.
     */
    public void retryIfDown() {
        Status current = status.get();
        if (current != Status.NOT_SUBSCRIBED && current != Status.FAILED
                && current != Status.CONNECTING) {
            return;
        }
        Instant lastAttempt = lastSubscribeAttempt.get();
        if (lastAttempt != null
                && lastAttempt.plus(marketDataConfig.resubscribeInterval()).isAfter(clock.now())) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            return;
        }
        lastSubscribeAttempt.set(clock.now());
        executor.submit(this::connectAndSubscribe);
    }

    private void onMarketData(MarketData message) {
        lastMessage.set(clock.now());
        QuotesBasic data = message.getData();
        if (data == null) {
            return;
        }
        InstrumentId instrumentId = resolve(data.getSymbol());
        if (instrumentId == null) {
            return;
        }
        Instant now = clock.now();

        Partial merged = switch (data) {
            case Snapshot snapshot ->
                    partials.computeIfAbsent(instrumentId, id -> Partial.empty())
                            .withTrade(snapshot, now);
            case com.webull.openapi.data.quotes.domain.Quote book ->
                    partials.computeIfAbsent(instrumentId, id -> Partial.empty())
                            .withBook(book, now);
            default -> null;
        };
        if (merged == null) {
            return;
        }
        partials.put(instrumentId, merged);
        merged.toQuote(instrumentId, clock.currentSession(), now).ifPresent(quote -> {
            latest.put(instrumentId, quote);
            for (Consumer<Quote> listener : listeners) {
                try {
                    listener.accept(quote);
                } catch (RuntimeException e) {
                    log.debug("Quote listener threw: {}", e.getMessage());
                }
            }
        });
    }

    private InstrumentId resolve(String symbol) {
        if (symbol == null) {
            return null;
        }
        return bySymbol.computeIfAbsent(symbol.toUpperCase(),
                s -> referenceData.resolve(s).map(Instrument::id).orElse(null));
    }

    private Set<String> equitySymbols() {
        Set<String> symbols = new LinkedHashSet<>();
        // Only the watchlist is subscribed. Candidates exist for historical scanning and
        // would otherwise turn a 10-symbol subscription into a few hundred.
        // Streaming is an equity feed. Crypto and event watchlists are polled instead.
        for (Instrument instrument : referenceData.watchlist(
                dev.aperture.instrument.TradableUniverse.EQUITY)) {
            symbols.add(instrument.primarySymbol());
            bySymbol.put(instrument.primarySymbol(), instrument.id());
        }
        return symbols;
    }

    private static boolean indicatesMissingSubscription(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("MARKET_DATA_NOT_SUBSCRIBED")
                    || message.contains("Insufficient permission")) {
                return true;
            }
        }
        return false;
    }

    private void closeQuietly() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
            // Closing a feed that is already gone is not worth reporting.
        }
    }

    @PreDestroy
    void shutdown() {
        closeQuietly();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Connection lifecycle, distinguishing "refused" from "broken". */
    public enum Status {
        NOT_CONFIGURED,
        CONNECTING,
        CONNECTED,
        STREAMING,
        /** Connected, but the account may not stream quotes. A billing state, not a fault. */
        NOT_SUBSCRIBED,
        FAILED
    }

    /**
     * Half-built quote, accumulating the trade side and the book side as their messages arrive.
     *
     * <p>Immutable: each message produces a new value rather than mutating shared state, so a
     * reader on the WebSocket thread can never observe a half-updated quote.
     */
    private record Partial(BigDecimal bid, BigDecimal bidSize, BigDecimal ask, BigDecimal askSize,
                           BigDecimal last, BigDecimal volume, BigDecimal previousClose,
                           Instant tradeTime, Instant bookTime) {

        static Partial empty() {
            return new Partial(null, null, null, null, null, null, null, null, null);
        }

        /** Folds in a SNAPSHOT message: last, volume and previous close. */
        Partial withTrade(Snapshot snapshot, Instant now) {
            BigDecimal price = WebullQuoteClient.decimal(snapshot.getPrice());
            if (price == null) {
                price = WebullQuoteClient.decimal(snapshot.getClose());
            }
            Instant at = snapshot.getLastTradeTime() == null
                    ? now : Instant.ofEpochMilli(snapshot.getLastTradeTime());
            return new Partial(bid, bidSize, ask, askSize,
                    price == null ? last : price,
                    keep(WebullQuoteClient.decimal(snapshot.getVolume()), volume),
                    keep(WebullQuoteClient.decimal(snapshot.getPreClose()), previousClose),
                    at, bookTime);
        }

        /** Folds in a QUOTE message: the top of book on each side. */
        Partial withBook(com.webull.openapi.data.quotes.domain.Quote book, Instant now) {
            AskBid bestBid = firstOf(book.getBids());
            AskBid bestAsk = firstOf(book.getAsks());
            return new Partial(
                    bestBid == null ? bid : keep(WebullQuoteClient.decimal(bestBid.getPrice()), bid),
                    bestBid == null ? bidSize : keep(WebullQuoteClient.decimal(bestBid.getSize()), bidSize),
                    bestAsk == null ? ask : keep(WebullQuoteClient.decimal(bestAsk.getPrice()), ask),
                    bestAsk == null ? askSize : keep(WebullQuoteClient.decimal(bestAsk.getSize()), askSize),
                    last, volume, previousClose, tradeTime,
                    book.getQuoteTime() == null ? now : Instant.ofEpochMilli(book.getQuoteTime()));
        }

        /**
         * Emits only once a last price exists. A book with no trade behind it is not yet a quote
         * this application is willing to show.
         */
        Optional<Quote> toQuote(InstrumentId instrumentId, TradingSession session, Instant now) {
            if (last == null || last.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Quote(
                    instrumentId,
                    Price.of(orZero(bid)), Quantity.of(orZero(bidSize)),
                    Price.of(orZero(ask)), Quantity.of(orZero(askSize)),
                    Price.of(last), Quantity.of(orZero(volume)),
                    Price.of(previousClose == null ? last : previousClose),
                    session, QuoteProvenance.LIVE_STREAM,
                    latestOf(tradeTime, bookTime, now), now));
        }

        private static AskBid firstOf(List<AskBid> levels) {
            return levels == null || levels.isEmpty() ? null : levels.get(0);
        }

        /** A field absent from this message keeps its previous value rather than resetting. */
        private static BigDecimal keep(BigDecimal fresh, BigDecimal existing) {
            return fresh == null ? existing : fresh;
        }

        private static BigDecimal orZero(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }

        private static Instant latestOf(Instant a, Instant b, Instant fallback) {
            if (a == null && b == null) {
                return fallback;
            }
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.isAfter(b) ? a : b;
        }
    }
}
