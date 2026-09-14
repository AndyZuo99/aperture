package dev.aperture.web;

import dev.aperture.corporate.ActionSource;
import dev.aperture.corporate.AdjustedSeries;
import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.common.Money;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.PriceHistory;
import dev.aperture.marketdata.Quote;
import dev.aperture.time.MarketClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quotes, history, depth, corporate actions and feed status. */
@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final MarketDataService marketData;
    private final PriceHistory priceHistory;
    private final ReferenceDataService referenceData;
    private final CorporateActionService corporateActions;
    private final ApiMapper mapper;
    private final MarketClock clock;
    private final dev.aperture.analysis.HistoryProvider historyProvider;
    private final dev.aperture.marketdata.WatchlistService watchlists;
    private final dev.aperture.marketdata.WebullInstrumentClient instrumentClient;
    private final dev.aperture.corporate.CorporateActionService actions;

    public MarketDataController(MarketDataService marketData,
                                PriceHistory priceHistory,
                                ReferenceDataService referenceData,
                                CorporateActionService corporateActions,
                                ApiMapper mapper,
                                MarketClock clock,
                                dev.aperture.analysis.HistoryProvider historyProvider,
                                dev.aperture.marketdata.WatchlistService watchlists,
                                dev.aperture.marketdata.WebullInstrumentClient instrumentClient) {
        this.marketData = marketData;
        this.priceHistory = priceHistory;
        this.referenceData = referenceData;
        this.corporateActions = corporateActions;
        this.mapper = mapper;
        this.clock = clock;
        this.historyProvider = historyProvider;
        this.watchlists = watchlists;
        this.instrumentClient = instrumentClient;
        this.actions = corporateActions;
    }

    /**
     * The watchlist for a universe, or every watched instrument when none is named.
     *
     * <p>The grid follows the selected account: a futures account has no use for a list of
     * equities it cannot buy.
     */
    @GetMapping("/quotes")
    public List<ApiDtos.QuoteView> quotes(@RequestParam(required = false) String universe) {
        Instant now = clock.now();
        List<ApiDtos.QuoteView> views = new ArrayList<>();
        var quotes = universe == null || universe.isBlank()
                ? marketData.allQuotes()
                : marketData.quotesFor(parseUniverse(universe));
        quotes.values().forEach(quote -> views.add(mapper.toQuoteView(quote, now)));
        return views;
    }

    /** Why a universe shows no prices - futures data is a separate entitlement. */
    @GetMapping("/quotes/availability")
    public Map<String, Object> quoteAvailability(
            @RequestParam(required = false) String universe) {
        dev.aperture.instrument.TradableUniverse resolved = parseUniverse(universe);
        return Map.of(
                "universe", resolved.name(),
                "quotable", watchlists.isQuotable(resolved),
                "reason", watchlists.unquotableReason(resolved));
    }

    @GetMapping("/quotes/{symbol}")
    public ResponseEntity<ApiDtos.QuoteView> quote(@PathVariable String symbol) {
        return marketData.quote(symbol)
                .map(quote -> ResponseEntity.ok(mapper.toQuoteView(quote, clock.now())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Daily history under an explicit adjustment policy.
     *
     * <p>The policy is a request parameter rather than a server-side default because the chart
     * and the performance panel legitimately want different bases, and the answer should never
     * depend on which one happened to be configured.
     */
    @GetMapping("/history/{symbol}")
    public ResponseEntity<ApiDtos.HistoryView> history(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "SPLITS_ONLY") String policy,
            @RequestParam(defaultValue = "1Y") String range,
            @RequestParam(required = false) String universe) {

        dev.aperture.instrument.TradableUniverse resolvedUniverse = parseUniverse(universe);
        AdjustmentPolicy adjustment = parsePolicy(policy);
        dev.aperture.analysis.ChartRange window = dev.aperture.analysis.ChartRange.parse(range);
        Instrument instrument = referenceData.resolve(symbol).orElse(null);

        AdjustedSeries series;
        boolean equity = resolvedUniverse == dev.aperture.instrument.TradableUniverse.EQUITY;

        if (window.isDaily() && equity && instrument != null
                && priceHistory.hasHistory(instrument.id())
                && window.bars() <= dev.aperture.analysis.HistoryProvider.DEFAULT_SESSIONS) {
            // Already backfilled and adjusted, and the window fits inside what is stored.
            series = priceHistory.recentSeries(instrument.id(), adjustment, window.bars());
        } else {
            List<Bar> fetched = window.trim(instrumentClient.history(
                    symbol, resolvedUniverse, window.timespan(), window.bars()));
            if (fetched.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (window.isDaily() && equity && instrument != null) {
                // Daily equity bars still carry corporate actions, whatever the window length -
                // a five-year chart spans more of them, not fewer.
                series = dev.aperture.corporate.PriceAdjuster.adjust(
                        fetched, actions.actionsFor(instrument.id()), adjustment);
            } else {
                series = new AdjustedSeries(fetched, adjustment, fetched.get(0).basis(), true,
                        window.isDaily()
                                ? "Corporate actions do not apply to " + resolvedUniverse.label()
                                  + "."
                                : "Intraday bars, so corporate actions are not restated - the "
                                  + "vendor's intraday series is already adjusted.");
            }
        }
        List<Bar> bars = series.bars();

        List<ApiDtos.ActionView> inWindow = new ArrayList<>();
        if (!bars.isEmpty() && instrument != null) {
            LocalDate from = bars.get(0).sessionDate();
            LocalDate to = bars.get(bars.size() - 1).sessionDate();
            corporateActions.recordedFor(instrument.id()).stream()
                    .filter(r -> !r.action().exDate().isBefore(from)
                            && !r.action().exDate().isAfter(to))
                    .forEach(r -> inWindow.add(mapper.toActionView(r)));
        }

        return ResponseEntity.ok(new ApiDtos.HistoryView(
                instrument == null ? symbol.toUpperCase() : instrument.primarySymbol(),
                adjustment.name(),
                adjustment.label(),
                adjustment.description(),
                series.basis().name(),
                series.basis().label(),
                series.basis().description(),
                series.satisfied(),
                series.note(),
                window.label(),
                window.description(),
                !window.isDaily(),
                bars.stream().map(mapper::toBarView).toList(),
                // Computed from the bars actually shown, not from stored history. A searched
                // symbol or a crypto pair has no entry in PriceHistory, and reading the stats
                // from there left the chart's headline figures blank for everything off the
                // watchlist.
                windowReturn(bars),
                annualisedVolatility(bars),
                extreme(bars, true),
                extreme(bars, false),
                averageVolume(bars),
                extremeBar(bars, true),
                extremeBar(bars, false),
                maxDrawdown(bars),
                bars.isEmpty() ? null : bars.get(0).sessionDate().toString(),
                bars.isEmpty() ? null : bars.get(bars.size() - 1).sessionDate().toString(),
                inWindow));
    }

    /**
     * The same window on all three bases, which is what makes the effect of a split visible as a
     * number instead of a claim.
     */
    @GetMapping("/history/{symbol}/compare")
    public ResponseEntity<Map<String, Object>> compare(@PathVariable String symbol) {
        Instrument instrument = referenceData.resolve(symbol).orElse(null);
        if (instrument == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> returns = new java.util.LinkedHashMap<>();
        for (AdjustmentPolicy policy : AdjustmentPolicy.values()) {
            priceHistory.returnOverWindow(instrument.id(), policy)
                    .ifPresent(value -> returns.put(policy.name(), value));
        }
        return ResponseEntity.ok(Map.of(
                "symbol", instrument.primarySymbol(),
                "returnPercentByPolicy", returns,
                "actions", corporateActions.recordedFor(instrument.id()).stream()
                        .map(mapper::toActionView).toList()));
    }

    /**
     * The current trading session, for the live view.
     *
     * <p>One-minute bars trimmed to the latest session date. The vendor returns a rolling window
     * spanning several days, so without the trim the "day range" quietly becomes a two-day range
     * and the open belongs to yesterday.
     */
    @GetMapping("/intraday/{symbol}")
    public ResponseEntity<ApiDtos.IntradayView> intraday(
            @PathVariable String symbol,
            @RequestParam(required = false) String universe) {

        dev.aperture.instrument.TradableUniverse resolved = parseUniverse(universe);
        if (resolved == dev.aperture.instrument.TradableUniverse.FUTURES) {
            return ResponseEntity.notFound().build();
        }
        // 400 one-minute bars comfortably covers a full session including extended hours.
        List<Bar> minuteBars = instrumentClient.history(symbol, resolved, "M1", 400);
        if (minuteBars.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Instrument instrument = referenceData.resolve(symbol).orElse(null);

        // The previous close comes from the daily series, not from the intraday window - the
        // earliest minute bar in that window is not the prior session's close.
        java.math.BigDecimal previousClose = null;
        var quote = marketData.quote(symbol);
        if (quote.isPresent() && quote.get().previousClose().isPositive()) {
            previousClose = quote.get().previousClose().value();
        } else {
            List<Bar> daily = instrumentClient.history(symbol, resolved, "D", 2);
            if (daily.size() >= 2) {
                previousClose = daily.get(daily.size() - 2).close().value();
            }
        }

        var session = dev.aperture.analysis.IntradaySession.from(
                symbol.toUpperCase(), minuteBars, previousClose);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var s = session.get();

        return ResponseEntity.ok(new ApiDtos.IntradayView(
                s.symbol(),
                instrument == null ? s.symbol() : instrument.name(),
                resolved.name(),
                s.sessionDate().toString(),
                clock.currentSession().label(),
                clock.isOpen(),
                s.bars().stream().map(mapper::toBarView).toList(),
                s.open(), s.high(), s.low(), s.last(), s.volume(),
                s.vwap().orElse(null),
                s.isAboveVwap().orElse(null),
                s.previousClose().orElse(null),
                s.changeFromOpen(), s.changeFromOpenPercent(),
                s.changeFromPreviousClose().orElse(null),
                s.changeFromPreviousClosePercent().orElse(null),
                s.rangePosition(),
                quote.map(q -> mapper.toQuoteView(q, clock.now())).orElse(null),
                s.sessionDate().equals(clock.currentTradingDate())
                        ? ""
                        : "The market has not opened since " + s.sessionDate()
                          + "; this is that session."));
    }

    /** Best bid and offer, as far as the entitlement permits. */
    @GetMapping("/depth/{symbol}")
    public ResponseEntity<ApiDtos.DepthView> depth(@PathVariable String symbol,
                                                   @RequestParam(defaultValue = "5") int levels) {
        Instrument instrument = referenceData.resolve(symbol).orElse(null);
        if (instrument == null) {
            return ResponseEntity.notFound().build();
        }
        return marketData.depth(instrument.id(), levels)
                .map(depth -> ResponseEntity.ok(mapper.toDepthView(
                        instrument.primarySymbol(), depth, entitlementNote(depth.levelCount()))))
                .orElseGet(() -> ResponseEntity.ok(new ApiDtos.DepthView(
                        instrument.primarySymbol(), List.of(), List.of(), 0,
                        "Book depth is not available on this subscription.")));
    }

    /**
     * Says what the displayed depth actually is. A single level is the real touch with the book
     * behind it invisible - which is a different statement from an empty book, and the UI must
     * never let one read as the other.
     */
    private static String entitlementNote(int levels) {
        if (levels <= 1) {
            return "Level 1 (best bid and offer). Nasdaq Basic does not carry depth behind the "
                    + "touch - the quoted sizes are real, the liquidity behind them is unknown.";
        }
        return "Level 2, " + levels + " levels per side.";
    }

    @GetMapping("/instruments")
    public List<Map<String, Object>> instruments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instrument instrument : referenceData.watchlist()) {
            rows.add(Map.of(
                    "symbol", instrument.primarySymbol(),
                    "name", instrument.name(),
                    "type", instrument.type().label(),
                    "hasHistory", priceHistory.hasHistory(instrument.id())));
        }
        return rows;
    }

    @GetMapping("/actions")
    public List<ApiDtos.ActionView> actions() {
        return corporateActions.all().stream().map(mapper::toActionView).toList();
    }

    /** Declares a split by hand, for an instrument no feed reports one for. */
    @PostMapping("/actions/split")
    public ResponseEntity<ApiDtos.ActionView> declareSplit(
            @RequestBody ApiDtos.DeclareSplitRequest request) {
        InstrumentId id = requireInstrument(request.symbol());
        corporateActions.declareSplit(id, LocalDate.parse(request.exDate()),
                request.newShares(), request.oldShares());
        return ResponseEntity.ok(corporateActions.recordedFor(id).stream()
                .filter(r -> r.source() == ActionSource.DECLARED)
                .reduce((first, second) -> second)
                .map(mapper::toActionView)
                .orElseThrow());
    }

    @PostMapping("/actions/dividend")
    public ResponseEntity<ApiDtos.ActionView> declareDividend(
            @RequestBody ApiDtos.DeclareDividendRequest request) {
        InstrumentId id = requireInstrument(request.symbol());
        LocalDate exDate = LocalDate.parse(request.exDate());
        LocalDate payDate = request.payDate() == null || request.payDate().isBlank()
                ? exDate : LocalDate.parse(request.payDate());
        corporateActions.declareDividend(id, exDate, payDate, Money.usd(request.amountPerShare()));
        return ResponseEntity.ok(corporateActions.recordedFor(id).stream()
                .filter(r -> r.source() == ActionSource.DECLARED)
                .reduce((first, second) -> second)
                .map(mapper::toActionView)
                .orElseThrow());
    }

    @GetMapping("/status")
    public ApiDtos.FeedStatusView status() {
        return mapper.toFeedStatusView(marketData.status());
    }

    private InstrumentId requireInstrument(String symbol) {
        return referenceData.resolve(symbol)
                .map(Instrument::id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown symbol: " + symbol));
    }

    /** Total return across the displayed window, in percent. */
    private static java.math.BigDecimal windowReturn(List<Bar> bars) {
        if (bars.size() < 2) {
            return null;
        }
        java.math.BigDecimal first = bars.get(0).close().value();
        if (first.signum() <= 0) {
            return null;
        }
        return bars.get(bars.size() - 1).close().value().subtract(first)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(first, 2, java.math.RoundingMode.HALF_EVEN);
    }

    /**
     * Annualised volatility of daily log returns, in percent.
     *
     * <p>Annualised with the 252-session convention even for crypto, which trades every day. It is
     * the figure everyone quotes, and switching the convention per asset class would make the
     * numbers incomparable across the very grid that shows them side by side.
     */
    private static java.math.BigDecimal annualisedVolatility(List<Bar> bars) {
        if (bars.size() < 20) {
            return null;
        }
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        for (int i = 1; i < bars.size(); i++) {
            double previous = bars.get(i - 1).close().value().doubleValue();
            double current = bars.get(i).close().value().doubleValue();
            if (previous <= 0 || current <= 0) {
                continue;
            }
            double logReturn = Math.log(current / previous);
            sum += logReturn;
            sumSquares += logReturn * logReturn;
            count++;
        }
        if (count < 2) {
            return null;
        }
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        if (variance <= 0) {
            return null;
        }
        return java.math.BigDecimal.valueOf(Math.sqrt(variance) * Math.sqrt(252) * 100)
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    /** Highest high or lowest low across the displayed window. */
    private static java.math.BigDecimal extreme(List<Bar> bars, boolean high) {
        java.math.BigDecimal found = null;
        for (Bar bar : bars) {
            java.math.BigDecimal value = high ? bar.high().value() : bar.low().value();
            if (found == null || (high ? value.compareTo(found) > 0 : value.compareTo(found) < 0)) {
                found = value;
            }
        }
        return found == null ? null : found.setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    private static java.math.BigDecimal averageVolume(List<Bar> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (Bar bar : bars) {
            total = total.add(bar.volume().value());
        }
        return total.divide(java.math.BigDecimal.valueOf(bars.size()), 0,
                java.math.RoundingMode.HALF_EVEN);
    }

    /** The best or worst single-bar move in the window, in percent. */
    private static java.math.BigDecimal extremeBar(List<Bar> bars, boolean best) {
        java.math.BigDecimal found = null;
        for (int i = 1; i < bars.size(); i++) {
            java.math.BigDecimal previous = bars.get(i - 1).close().value();
            if (previous.signum() <= 0) {
                continue;
            }
            java.math.BigDecimal move = bars.get(i).close().value().subtract(previous)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .divide(previous, 2, java.math.RoundingMode.HALF_EVEN);
            if (found == null || (best ? move.compareTo(found) > 0 : move.compareTo(found) < 0)) {
                found = move;
            }
        }
        return found;
    }

    /**
     * The deepest peak-to-trough fall inside the window, in percent.
     *
     * <p>Measured against the running maximum rather than the starting price: a position opened at
     * the window's high has a very different experience from the window's total return, and this
     * is the number that says how much of it you would have had to sit through.
     */
    private static java.math.BigDecimal maxDrawdown(List<Bar> bars) {
        if (bars.size() < 2) {
            return null;
        }
        java.math.BigDecimal peak = null;
        java.math.BigDecimal worst = java.math.BigDecimal.ZERO;
        for (Bar bar : bars) {
            java.math.BigDecimal close = bar.close().value();
            if (peak == null || close.compareTo(peak) > 0) {
                peak = close;
            }
            if (peak.signum() > 0) {
                java.math.BigDecimal fall = close.subtract(peak)
                        .multiply(java.math.BigDecimal.valueOf(100))
                        .divide(peak, 2, java.math.RoundingMode.HALF_EVEN);
                if (fall.compareTo(worst) < 0) {
                    worst = fall;
                }
            }
        }
        return worst;
    }

    private static dev.aperture.instrument.TradableUniverse parseUniverse(String name) {
        if (name == null || name.isBlank()) {
            return dev.aperture.instrument.TradableUniverse.EQUITY;
        }
        try {
            return dev.aperture.instrument.TradableUniverse.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown universe: " + name
                    + ". Expected EQUITY, CRYPTO, EVENT or FUTURES.");
        }
    }

    private static AdjustmentPolicy parsePolicy(String name) {
        try {
            return AdjustmentPolicy.valueOf(name.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown adjustment policy: " + name
                    + ". Expected NONE, SPLITS_ONLY or TOTAL_RETURN.");
        }
    }
}
