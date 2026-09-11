package dev.aperture.marketdata;

import com.webull.openapi.core.common.dict.Category;
import com.webull.openapi.data.DataClient;
import com.webull.openapi.data.quotes.domain.AskBid;
import com.webull.openapi.data.quotes.domain.BatchBarResponse;
import com.webull.openapi.data.quotes.domain.DividendCalendar;
import com.webull.openapi.data.quotes.domain.NBar;
import com.webull.openapi.data.quotes.domain.Snapshot;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.CorporateAction;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.SecurityType;
import dev.aperture.time.MarketCalendar;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Webull REST market-data client: batch Level 1 quotes, daily bars, dividends and reference
 * data.
 *
 * <h2>Batch snapshots are the primary quote path</h2>
 *
 * <p>{@code getSnapshots} returns a <em>complete</em> Level 1 quote in a single call for up to a
 * hundred symbols - both sides of the book with sizes <em>and</em> the last print, open, high,
 * low, previous close and volume. The streaming feed splits that same information across two
 * message types that have to be merged, so this is the simpler and more reliable path; streaming
 * exists on top of it for push latency, not for completeness.
 *
 * <h2>The overnight flag</h2>
 *
 * <p>{@code overnight=true} requests the overnight session, which is a <strong>separate paid
 * product</strong>. Asking for it without that entitlement fails the whole request with
 * {@code 403 MARKET_DATA_NOT_SUBSCRIBED: please subscribe to NIGHT TRADING STOCK QUOTES} - and
 * because the message says "market data not subscribed", it reads exactly like the account having
 * no market-data entitlement at all. It is not. {@code extendHour=true, overnight=false} returns
 * pre- and post-market prices on a plain Nasdaq Basic subscription.
 */
@Component
public class WebullQuoteClient implements QuoteSource {

    private static final Logger log = LoggerFactory.getLogger(WebullQuoteClient.class);

    /** Vendor cap on symbols per batch request. */
    private static final int MAX_BATCH_SYMBOLS = 100;

    /**
     * Vendor bar timestamps look like {@code 2026-09-10T04:00:00.000+0000}. A bare
     * {@code OffsetDateTime.parse} rejects {@code +0000} - it wants {@code +00:00} or {@code Z} -
     * so the offset is accepted in all three spellings. A guarded parse that swallowed the failure
     * here would silently empty the entire price history.
     */
    static final DateTimeFormatter VENDOR_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendPattern("[XXX][XX][X]")
            .toFormatter();

    private final WebullClientHolder holder;
    private final MarketClock clock;

    private final AtomicReference<Instant> lastSuccessfulFetch = new AtomicReference<>();
    private volatile boolean entitlementConfirmed = false;
    private volatile boolean entitlementMissing = false;
    private volatile String lastError = null;
    private volatile int maxDepthLevels = Integer.MAX_VALUE;
    private volatile boolean depthRefused = false;

    public WebullQuoteClient(WebullClientHolder holder, MarketClock clock) {
        this.holder = holder;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "WEBULL_REST";
    }

    @Override
    public QuoteProvenance provenance() {
        return QuoteProvenance.LIVE_REST;
    }

    @Override
    public boolean isAvailable() {
        return holder.isConnected() && entitlementConfirmed;
    }

    @Override
    public Optional<Quote> latest(InstrumentId instrumentId) {
        return Optional.empty();
    }

    @Override
    public Map<InstrumentId, Quote> snapshot(Set<InstrumentId> instrumentIds) {
        return Map.of();
    }

    /**
     * Batch Level 1 for the given instruments.
     *
     * @param instruments resolved instruments, so that the right vendor category is used per
     *     symbol - ETFs quote under {@code US_ETF} and would simply not be found under
     *     {@code US_STOCK}
     */
    public Map<InstrumentId, Quote> quotes(List<Instrument> instruments) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty() || instruments.isEmpty() || entitlementMissing) {
            return Map.of();
        }
        Map<String, InstrumentId> bySymbol = new LinkedHashMap<>();
        Map<Category, Set<String>> byCategory = new LinkedHashMap<>();
        for (Instrument instrument : instruments) {
            bySymbol.put(instrument.primarySymbol(), instrument.id());
            byCategory.computeIfAbsent(categoryFor(instrument.type()), c -> new LinkedHashSet<>())
                    .add(instrument.primarySymbol());
        }

        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        for (Map.Entry<Category, Set<String>> entry : byCategory.entrySet()) {
            for (List<String> batch : partition(List.copyOf(entry.getValue()))) {
                out.putAll(fetchBatch(client.get(), entry.getKey(), Set.copyOf(batch), bySymbol));
            }
        }
        return out;
    }

    private Map<InstrumentId, Quote> fetchBatch(DataClient client, Category category,
                                                Set<String> symbols,
                                                Map<String, InstrumentId> bySymbol) {
        Map<InstrumentId, Quote> out = new LinkedHashMap<>();
        try {
            // extendHour=true gives pre/post-market prints; overnight=false because the overnight
            // session is a separate subscription and asking for it fails the entire request.
            List<Snapshot> snapshots =
                    client.getSnapshots(symbols, category.name(), Boolean.TRUE, Boolean.FALSE);
            if (snapshots == null || snapshots.isEmpty()) {
                return out;
            }
            Instant now = clock.now();
            for (Snapshot snapshot : snapshots) {
                InstrumentId id = bySymbol.get(upper(snapshot.getSymbol()));
                if (id == null) {
                    continue;
                }
                toQuote(id, snapshot, now).ifPresent(quote -> out.put(id, quote));
            }
            if (!out.isEmpty()) {
                entitlementConfirmed = true;
                lastError = null;
                lastSuccessfulFetch.set(now);
            }
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            if (indicatesMissingEntitlement(e)) {
                entitlementMissing = true;
                log.warn("Webull authenticated but refused market data ({}). This is a billing "
                        + "state, not a transient failure, so Aperture will stop retrying and use "
                        + "the simulated feed. Subscribe under Advanced Quotes -> OpenAPI "
                        + "Advanced Quotes in the developer portal.", e.getMessage());
            } else {
                log.warn("Webull snapshot request failed for {} {} symbols: {}",
                        symbols.size(), category, e.getMessage());
            }
        }
        return out;
    }

    private Optional<Quote> toQuote(InstrumentId instrumentId, Snapshot snapshot, Instant now) {
        // Prefer the extended-hours print when one exists: outside regular hours it is the live
        // price, while `price` has stopped at the regular-session close.
        BigDecimal last = firstNonNull(
                decimal(snapshot.getExtendHourLastPrice()),
                decimal(snapshot.getPrice()),
                decimal(snapshot.getClose()));
        if (last == null || last.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal previousClose = orDefault(decimal(snapshot.getPreClose()), last);
        Instant eventTime = epochMillis(snapshot.getQuoteTime())
                .or(() -> epochMillis(snapshot.getExtendHourLastTradeTime()))
                .or(() -> epochMillis(snapshot.getLastTradeTime()))
                .orElse(now);

        // Session volume is cumulative, not per-trade. Regular volume plus the extended-hours
        // tally is the day's total; adding the same field twice would double-count it.
        BigDecimal volume = orDefault(decimal(snapshot.getVolume()), BigDecimal.ZERO)
                .add(orDefault(decimal(snapshot.getExtendHourVolume()), BigDecimal.ZERO));

        return Optional.of(new Quote(
                instrumentId,
                Price.of(orDefault(decimal(snapshot.getBid()), BigDecimal.ZERO)),
                Quantity.of(orDefault(decimal(snapshot.getBidSize()), BigDecimal.ZERO)),
                Price.of(orDefault(decimal(snapshot.getAsk()), BigDecimal.ZERO)),
                Quantity.of(orDefault(decimal(snapshot.getAskSize()), BigDecimal.ZERO)),
                Price.of(last),
                Quantity.of(volume),
                Price.of(previousClose),
                clock.currentSession(),
                QuoteProvenance.LIVE_REST,
                eventTime,
                now));
    }

    /** Daily OHLCV history, batched. */
    public Map<InstrumentId, List<Bar>> dailyBars(List<Instrument> instruments, int count) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty() || instruments.isEmpty()) {
            return Map.of();
        }
        Map<String, Instrument> bySymbol = new LinkedHashMap<>();
        for (Instrument instrument : instruments) {
            bySymbol.put(instrument.primarySymbol(), instrument);
        }
        Map<InstrumentId, List<Bar>> out = new LinkedHashMap<>();
        for (List<String> batch : partition(List.copyOf(bySymbol.keySet()))) {
            try {
                BatchBarResponse response =
                        client.get().getBatchBars(batch, Category.US_STOCK.name(), "D", count);
                if (response == null || response.getResult() == null) {
                    continue;
                }
                for (NBar entry : response.getResult()) {
                    Instrument instrument = bySymbol.get(upper(entry.getSymbol()));
                    // NBar's accessor is getResult(), not getBar() - its toString prints "bar=",
                    // which is misleading.
                    if (instrument == null || entry.getResult() == null) {
                        continue;
                    }
                    List<Bar> bars = new ArrayList<>();
                    for (com.webull.openapi.data.quotes.domain.Bar vendorBar : entry.getResult()) {
                        toBar(instrument.id(), vendorBar).ifPresent(bars::add);
                    }
                    if (!bars.isEmpty()) {
                        bars.sort(java.util.Comparator.comparing(Bar::sessionDate));
                        out.put(instrument.id(), bars);
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Webull daily bars failed for {} symbols: {}", batch.size(), e.getMessage());
            }
        }
        return out;
    }

    private Optional<Bar> toBar(InstrumentId id, com.webull.openapi.data.quotes.domain.Bar vendor) {
        BigDecimal open = decimal(vendor.getOpen());
        BigDecimal high = decimal(vendor.getHigh());
        BigDecimal low = decimal(vendor.getLow());
        BigDecimal close = decimal(vendor.getClose());
        if (open == null || high == null || low == null || close == null) {
            return Optional.empty();
        }
        Instant start;
        try {
            start = OffsetDateTime.parse(vendor.getTime(), VENDOR_TIMESTAMP).toInstant();
        } catch (RuntimeException e) {
            log.warn("Unparseable vendor bar timestamp '{}': {}", vendor.getTime(), e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new Bar(
                id,
                start.atZone(MarketCalendar.EXCHANGE_ZONE).toLocalDate(),
                Price.of(open), Price.of(high), Price.of(low), Price.of(close),
                Quantity.of(orDefault(decimal(vendor.getVolume()), BigDecimal.ZERO)),
                // Verified against live data: Webull's daily bars are ALREADY split-adjusted.
                // NVDA on 2023-07-05 returns 42.19, not the ~421.90 it traded at before the June
                // 2024 10-for-1 split. Labelling these RAW would make the adjuster divide by ten
                // a second time.
                PriceBasis.SPLIT_ADJUSTED));
    }

    /**
     * Cash dividends from the vendor's dividend calendar.
     *
     * <p>Argument order is (symbol, category) - passing them the other way round returns
     * {@code 417 UNSUPPORTED_CATEGORY} naming the symbol as the bad category, which is a confusing
     * way to be told the arguments are swapped.
     *
     * <p>Note this is a <em>calendar</em>: it is forward-looking and does not backfill years of
     * dividend history, so it cannot on its own build a long total-return series.
     */
    public List<CorporateAction> dividends(Instrument instrument) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            List<DividendCalendar> rows = client.get().getDividendCalendar(
                    instrument.primarySymbol(), categoryFor(instrument.type()).name());
            if (rows == null) {
                return List.of();
            }
            List<CorporateAction> actions = new ArrayList<>();
            for (DividendCalendar row : rows) {
                toDividend(instrument.id(), row).ifPresent(actions::add);
            }
            return actions;
        } catch (RuntimeException e) {
            log.debug("Dividend calendar failed for {}: {}",
                    instrument.primarySymbol(), e.getMessage());
            return List.of();
        }
    }

    private Optional<CorporateAction> toDividend(InstrumentId id, DividendCalendar row) {
        BigDecimal amount = decimal(row.getAmount());
        LocalDate exDate = date(row.getExDivDate());
        if (amount == null || amount.signum() <= 0 || exDate == null) {
            return Optional.empty();
        }
        // Pay date is occasionally missing; the ex-date is what the adjustment keys off, so fall
        // back to it rather than dropping an otherwise usable dividend.
        LocalDate payDate = orDefault(date(row.getPayDate()), exDate);
        if (payDate.isBefore(exDate)) {
            payDate = exDate;
        }
        return Optional.of(new CorporateAction.CashDividend(id, exDate, payDate, Money.usd(amount)));
    }

    /** Resolves exact symbols through the vendor. There is no fuzzy search endpoint. */
    public Set<String> knownSymbols(Set<String> candidates, Category category) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty() || candidates.isEmpty()) {
            return Set.of();
        }
        Set<String> known = new LinkedHashSet<>();
        for (List<String> batch : partition(List.copyOf(candidates))) {
            try {
                var found = client.get().getInstruments(Set.copyOf(batch), category.name());
                if (found != null) {
                    found.forEach(i -> known.add(upper(i.getSymbol())));
                }
            } catch (RuntimeException e) {
                log.debug("Symbol validation failed for {} symbols: {}", batch.size(), e.getMessage());
            }
        }
        return known;
    }

    /** Looks a single symbol up, trying equity then ETF categories. */
    public Optional<VendorInstrument> lookup(String symbol) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty()) {
            return Optional.empty();
        }
        for (Category category : List.of(Category.US_STOCK, Category.US_ETF)) {
            try {
                var found = client.get()
                        .getInstruments(Set.of(symbol.toUpperCase()), category.name());
                if (found == null || found.isEmpty()) {
                    continue;
                }
                var vendor = found.get(0);
                return Optional.of(new VendorInstrument(
                        vendor.getSymbol(), vendor.getName(), vendor.getInstrumentId(),
                        vendor.getExchangeCode(), vendor.getCurrency(),
                        category == Category.US_ETF ? SecurityType.ETF : SecurityType.COMMON_STOCK));
            } catch (RuntimeException e) {
                log.debug("Lookup of {} in {} failed: {}", symbol, category, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Best bid and offer with sizes.
     *
     * <p>Nasdaq Basic is a BBO feed, so a request for more than one level is rejected with
     * {@code depth not more than 1}. The cap is parsed out of that message and honoured from then
     * on rather than letting every subsequent request fail the same way.
     */
    public Optional<MarketDepth> depth(Instrument instrument, int levels) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty() || depthRefused) {
            return Optional.empty();
        }
        int requested = Math.min(levels, maxDepthLevels);
        try {
            var quote = client.get().getQuote(
                    instrument.primarySymbol(),
                    categoryFor(instrument.type()).name(),
                    String.valueOf(requested),
                    Boolean.FALSE);
            if (quote == null) {
                return Optional.empty();
            }
            Instant now = clock.now();
            Instant eventTime = quote.getQuoteTime() == null
                    ? now : Instant.ofEpochMilli(quote.getQuoteTime());
            return Optional.of(new MarketDepth(
                    instrument.id(), toLevels(quote.getBids()), toLevels(quote.getAsks()),
                    eventTime, now));
        } catch (RuntimeException e) {
            Optional<Integer> cap = parseDepthCap(e);
            if (cap.isPresent() && cap.get() < requested) {
                maxDepthLevels = cap.get();
                log.info("Webull caps book depth at {} level(s) for this entitlement "
                        + "(Nasdaq Basic is best-bid-and-offer only); honouring that from now on.",
                        maxDepthLevels);
                return depth(instrument, maxDepthLevels);
            }
            if (indicatesMissingEntitlement(e)) {
                depthRefused = true;
                log.info("Book depth is not included in this subscription; Level 1 is unaffected.");
            }
            return Optional.empty();
        }
    }

    public Optional<Integer> permittedDepthLevels() {
        return maxDepthLevels == Integer.MAX_VALUE ? Optional.empty() : Optional.of(maxDepthLevels);
    }

    public boolean isEntitlementMissing() {
        return entitlementMissing;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError).or(holder::detail);
    }

    public Optional<Instant> lastSuccessfulFetch() {
        return Optional.ofNullable(lastSuccessfulFetch.get());
    }

    private static List<MarketDepth.Level> toLevels(List<AskBid> levels) {
        if (levels == null) {
            return List.of();
        }
        List<MarketDepth.Level> out = new ArrayList<>();
        for (AskBid level : levels) {
            BigDecimal price = decimal(level.getPrice());
            BigDecimal size = decimal(level.getSize());
            if (price == null || price.signum() <= 0 || size == null || size.signum() <= 0) {
                continue;
            }
            out.add(new MarketDepth.Level(Price.of(price), Quantity.of(size)));
        }
        return out;
    }

    private static Optional<Integer> parseDepthCap(RuntimeException e) {
        Pattern pattern = Pattern.compile("depth not more than (\\d+)");
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(t.getMessage());
            if (matcher.find()) {
                try {
                    return Optional.of(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a failure is the account lacking a market-data subscription.
     *
     * <p>Deliberately does <em>not</em> match the night-trading refusal: that message also says
     * {@code MARKET_DATA_NOT_SUBSCRIBED}, but it means one optional product is missing, not that
     * the feed is dead. Treating it as a missing entitlement would switch a perfectly good live
     * feed off for the rest of the run.
     */
    private static boolean indicatesMissingEntitlement(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("NIGHT TRADING")) {
                return false;
            }
            if (message.contains("MARKET_DATA_NOT_SUBSCRIBED")
                    || message.contains("subscribe to STOCK QUOTES")) {
                return true;
            }
        }
        return false;
    }

    static Category categoryFor(SecurityType type) {
        return type.isFundLike() ? Category.US_ETF : Category.US_STOCK;
    }

    private static List<List<String>> partition(List<String> symbols) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += MAX_BATCH_SYMBOLS) {
            batches.add(symbols.subList(i, Math.min(i + MAX_BATCH_SYMBOLS, symbols.size())));
        }
        return batches;
    }

    private static Optional<Instant> epochMillis(Long value) {
        return value == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    /** What the vendor knows about a symbol, before it becomes an {@link Instrument}. */
    public record VendorInstrument(String symbol, String name, String vendorInstrumentId,
                                   String exchangeCode, String currency,
                                   SecurityType securityType) {
    }
}
