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

    public MarketDataController(MarketDataService marketData,
                                PriceHistory priceHistory,
                                ReferenceDataService referenceData,
                                CorporateActionService corporateActions,
                                ApiMapper mapper,
                                MarketClock clock) {
        this.marketData = marketData;
        this.priceHistory = priceHistory;
        this.referenceData = referenceData;
        this.corporateActions = corporateActions;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Every watched instrument's current quote. */
    @GetMapping("/quotes")
    public List<ApiDtos.QuoteView> quotes() {
        Instant now = clock.now();
        List<ApiDtos.QuoteView> views = new ArrayList<>();
        marketData.allQuotes().values().forEach(quote -> views.add(mapper.toQuoteView(quote, now)));
        return views;
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
            @RequestParam(defaultValue = "120") int days) {

        Instrument instrument = referenceData.resolve(symbol).orElse(null);
        if (instrument == null) {
            return ResponseEntity.notFound().build();
        }
        AdjustmentPolicy adjustment = parsePolicy(policy);
        AdjustedSeries series =
                priceHistory.recentSeries(instrument.id(), adjustment, Math.max(1, days));
        List<Bar> bars = series.bars();

        List<ApiDtos.ActionView> inWindow = new ArrayList<>();
        if (!bars.isEmpty()) {
            LocalDate from = bars.get(0).sessionDate();
            LocalDate to = bars.get(bars.size() - 1).sessionDate();
            corporateActions.recordedFor(instrument.id()).stream()
                    .filter(r -> !r.action().exDate().isBefore(from)
                            && !r.action().exDate().isAfter(to))
                    .forEach(r -> inWindow.add(mapper.toActionView(r)));
        }

        return ResponseEntity.ok(new ApiDtos.HistoryView(
                instrument.primarySymbol(),
                adjustment.name(),
                adjustment.label(),
                adjustment.description(),
                series.basis().name(),
                series.basis().label(),
                series.basis().description(),
                series.satisfied(),
                series.note(),
                bars.stream().map(mapper::toBarView).toList(),
                priceHistory.returnOverWindow(instrument.id(), adjustment).orElse(null),
                priceHistory.annualisedVolatility(instrument.id()).orElse(null),
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

    private static AdjustmentPolicy parsePolicy(String name) {
        try {
            return AdjustmentPolicy.valueOf(name.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown adjustment policy: " + name
                    + ". Expected NONE, SPLITS_ONLY or TOTAL_RETURN.");
        }
    }
}
