package dev.aperture.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aperture.account.AccountBalance;
import dev.aperture.account.AccountPosition;
import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.analysis.HistoryProvider;
import dev.aperture.analysis.TrendAnalyzer;
import dev.aperture.analysis.TrendStatistics;
import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.corporate.RecordedAction;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentCatalog;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.instrument.TradableInstrument;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.PriceHistory;
import dev.aperture.marketdata.Quote;
import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Everything the LLM analyst is allowed to see.
 *
 * <h2>Safety is structural, not procedural</h2>
 *
 * <p>This class has <strong>no mutating method</strong>. Not a disabled one, not one behind a
 * permission check - there is simply no method here that places an order, cancels one, moves cash
 * or changes configuration. The model cannot trade because nothing it can reach is capable of
 * trading, which is a far stronger guarantee than a prompt asking it not to.
 *
 * <p>{@code AnalystToolkitIsReadOnlyTest} enforces this by reflection: the build fails if a public
 * method is added here whose name so much as suggests mutation. That test is the actual control;
 * this comment is only its explanation.
 */
@Component
public class AnalystToolkit {

    private final ReferenceDataService referenceData;
    private final MarketDataService marketData;
    private final PriceHistory priceHistory;
    private final CorporateActionService corporateActions;
    private final AccountService accounts;
    private final HistoryProvider history;
    private final InstrumentCatalog catalog;
    private final ObjectMapper json;

    public AnalystToolkit(ReferenceDataService referenceData,
                          MarketDataService marketData,
                          PriceHistory priceHistory,
                          CorporateActionService corporateActions,
                          AccountService accounts,
                          HistoryProvider history,
                          InstrumentCatalog catalog,
                          ObjectMapper json) {
        this.referenceData = referenceData;
        this.marketData = marketData;
        this.priceHistory = priceHistory;
        this.corporateActions = corporateActions;
        this.accounts = accounts;
        this.history = history;
        this.catalog = catalog;
        this.json = json;
    }

    /** Every instrument Aperture is following, with whether history is loaded. */
    public Map<String, Object> listInstruments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instrument instrument : referenceData.watchlist()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", instrument.primarySymbol());
            row.put("name", instrument.name());
            row.put("type", instrument.type().label());
            row.put("hasHistory", priceHistory.hasHistory(instrument.id()));
            rows.add(row);
        }
        return Map.of("instruments", rows, "count", rows.size());
    }

    /** Current Level 1 for the given symbols, with the provenance of each quote. */
    public Map<String, Object> quotes(List<String> symbols) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        Map<String, Instrument> instruments = new LinkedHashMap<>();

        for (String symbol : symbols) {
            Optional<Instrument> instrument = referenceData.resolve(symbol);
            if (instrument.isEmpty()) {
                unknown.add(symbol);
                continue;
            }
            String primary = instrument.get().primarySymbol();
            instruments.putIfAbsent(primary, instrument.get());
            if (!resolved.contains(primary)) {
                resolved.add(primary);
            }
        }

        // On demand, not cache-only. Only the watchlist is streamed, so reading the cache here
        // meant the ranked candidate pool - the whole reason the pool is wider than the watchlist
        // - came back with no bid or ask, and the model correctly refused to price names it had
        // no quote for. One batched snapshot call covers the shortlist instead.
        Map<String, Quote> fetched = marketData.quotesOnDemand(resolved);

        List<String> unquoted = new ArrayList<>();
        for (String symbol : resolved) {
            Quote quote = fetched.get(symbol);
            if (quote == null) {
                unquoted.add(symbol);
                continue;
            }
            rows.add(quoteRow(instruments.get(symbol), quote));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quotes", rows);
        if (!unknown.isEmpty()) {
            out.put("unknownSymbols", unknown);
        }
        // Said explicitly rather than left as a gap in the list. A symbol that silently vanishes
        // reads as an empty response, which is indistinguishable from the tool being broken.
        if (!unquoted.isEmpty()) {
            out.put("noQuoteAvailable", unquoted);
            out.put("noQuoteReason", "The vendor returned no snapshot for these. Futures are not "
                    + "entitled on this account; anything else is usually an untraded or delisted "
                    + "symbol. Their last daily close is still available through get_history.");
        }
        return out;
    }

    private Map<String, Object> quoteRow(Instrument instrument, Quote quote) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", instrument.primarySymbol());
        row.put("last", quote.last().toDisplay());
        row.put("bid", quote.bid().toDisplay());
        row.put("ask", quote.ask().toDisplay());
        row.put("bidSize", quote.bidSize().toDisplay());
        row.put("askSize", quote.askSize().toDisplay());
        row.put("spreadBps", quote.spreadBasisPoints());
        row.put("previousClose", quote.previousClose().toDisplay());
        row.put("changePercent", quote.changePercent());
        row.put("volume", quote.volume().toDisplay());
        row.put("session", quote.session().label());
        // The model is told where the number came from, so it can qualify its own conclusions
        // rather than treating a simulated price as a market observation.
        row.put("dataSource", quote.provenance().label());
        row.put("isLiveMarketData", quote.isLive());
        return row;
    }

    /**
     * Daily bars under an explicit adjustment policy.
     *
     * @param policyName one of NONE, SPLITS_ONLY, TOTAL_RETURN
     */
    public Map<String, Object> priceHistory(String symbol, String policyName, int days) {
        Optional<Instrument> instrument = referenceData.resolve(symbol);
        if (instrument.isEmpty()) {
            return Map.of("error", "Unknown symbol: " + symbol);
        }
        AdjustmentPolicy policy = parsePolicy(policyName);
        var series = priceHistory.recentSeries(instrument.get().id(), policy, Math.max(1, days));
        List<Bar> bars = series.bars();
        if (bars.isEmpty()) {
            return Map.of("error", "No price history loaded for " + symbol
                    + ". History requires a live Webull connection.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Bar bar : bars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", bar.sessionDate().toString());
            row.put("open", bar.open().toDisplay());
            row.put("high", bar.high().toDisplay());
            row.put("low", bar.low().toDisplay());
            row.put("close", bar.close().toDisplay());
            row.put("volume", bar.volume().toDisplay());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", instrument.get().primarySymbol());
        out.put("requestedPolicy", policy.name());
        // What the series actually IS, which is not always what was asked for. The model must
        // describe the basis it received, not the one it requested.
        out.put("actualBasis", series.basis().name());
        out.put("basisMeaning", series.basis().description());
        out.put("requestSatisfied", series.satisfied());
        if (!series.note().isBlank()) {
            out.put("note", series.note());
        }
        out.put("bars", rows);
        priceHistory.returnOverWindow(instrument.get().id(), policy)
                .ifPresent(r -> out.put("windowReturnPercent", r));
        priceHistory.annualisedVolatility(instrument.get().id())
                .ifPresent(v -> out.put("annualisedVolatilityPercent", v));
        return out;
    }

    /** Recorded splits and dividends, with the source of each. */
    public Map<String, Object> corporateActions(String symbol) {
        List<RecordedAction> actions;
        if (symbol == null || symbol.isBlank()) {
            actions = corporateActions.all();
        } else {
            Optional<Instrument> instrument = referenceData.resolve(symbol);
            if (instrument.isEmpty()) {
                return Map.of("error", "Unknown symbol: " + symbol);
            }
            actions = corporateActions.recordedFor(instrument.get().id());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecordedAction recorded : actions) {
            Map<String, Object> row = new LinkedHashMap<>();
            referenceData.byId(recorded.action().instrumentId())
                    .ifPresent(i -> row.put("symbol", i.primarySymbol()));
            row.put("exDate", recorded.action().exDate().toString());
            row.put("description", recorded.action().describe());
            row.put("affectsShareCount", recorded.action().affectsShareCount());
            row.put("source", recorded.source().label());
            row.put("sourceMeaning", recorded.source().description());
            rows.add(row);
        }
        return Map.of("actions", rows, "count", rows.size());
    }

    /**
     * The same window computed three ways, so the effect of corporate actions is visible as a
     * number rather than asserted.
     *
     * <p>This is the tool that answers "is this stock down 90% or did it split", which is the
     * question an unadjusted series makes impossible to answer.
     */
    public Map<String, Object> compareAdjustments(String symbol) {
        Optional<Instrument> instrument = referenceData.resolve(symbol);
        if (instrument.isEmpty()) {
            return Map.of("error", "Unknown symbol: " + symbol);
        }
        if (!priceHistory.hasHistory(instrument.get().id())) {
            return Map.of("error", "No price history loaded for " + symbol);
        }
        Map<String, Object> returns = new LinkedHashMap<>();
        for (AdjustmentPolicy policy : AdjustmentPolicy.values()) {
            priceHistory.returnOverWindow(instrument.get().id(), policy)
                    .ifPresent(r -> returns.put(policy.name(), r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", instrument.get().primarySymbol());
        out.put("windowReturnPercentByPolicy", returns);
        out.put("actionsInWindow",
                corporateActions.recordedFor(instrument.get().id()).stream()
                        .map(r -> r.action().exDate() + ": " + r.action().describe())
                        .toList());
        BigDecimal unadjusted = (BigDecimal) returns.get(AdjustmentPolicy.NONE.name());
        BigDecimal adjusted = (BigDecimal) returns.get(AdjustmentPolicy.SPLITS_ONLY.name());
        if (unadjusted != null && adjusted != null) {
            out.put("differencePercentagePoints",
                    adjusted.subtract(unadjusted).setScale(2, RoundingMode.HALF_EVEN));
            out.put("interpretation", unadjusted.compareTo(adjusted) == 0
                    ? "No corporate action affects this window; the raw series is already correct."
                    : "The raw series is distorted by a corporate action. Use the adjusted figure.");
        }
        return out;
    }

    /** Balances and positions for an environment, as the broker reports them. */
    public Map<String, Object> accountSummary(String environmentName) {
        TradingEnvironment environment = environmentName == null || environmentName.isBlank()
                ? accounts.defaultEnvironment()
                : TradingEnvironment.parseOrSandbox(environmentName);

        List<BrokerAccount> brokerAccounts = accounts.accounts(environment);
        if (brokerAccounts.isEmpty()) {
            return Map.of("error", "No accounts available in " + environment.label()
                    + ". This usually means credentials for that environment are not configured.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BrokerAccount account : brokerAccounts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account", account.displayName());
            row.put("accountType", account.accountType());
            row.put("accountClass", account.accountClass());
            AccountBalance balance = accounts.balance(account);
            balance.netLiquidationValue().ifPresent(v -> row.put("netLiquidationValue", v.toDisplay()));
            balance.totalCash().ifPresent(v -> row.put("cash", v.toDisplay()));
            balance.buyingPower().ifPresent(v -> row.put("buyingPower", v.toDisplay()));
            balance.unrealizedProfitLoss().ifPresent(v -> row.put("unrealizedPnl", v.toDisplay()));

            List<Map<String, Object>> positions = new ArrayList<>();
            for (AccountPosition position : accounts.positions(account)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("symbol", position.symbol());
                p.put("quantity", position.quantity().toDisplay());
                p.put("costPrice", position.costPrice().toDisplay());
                p.put("lastPrice", position.lastPrice().toDisplay());
                position.marketValue().ifPresent(v -> p.put("marketValue", v.toDisplay()));
                position.unrealizedProfitLoss().ifPresent(v -> p.put("unrealizedPnl", v.toDisplay()));
                positions.add(p);
            }
            row.put("positions", positions);
            rows.add(row);
        }
        return Map.of("environment", environment.label(), "accounts", rows);
    }

    /**
     * How often this instrument has historically reached a range of gains within a horizon.
     *
     * <p>This is the tool that makes "should profit within a month" checkable. For every
     * overlapping historical window it asks whether the high <em>touched</em> each target -
     * touch, not close, because that is what fills a resting sell limit.
     */
    public Map<String, Object> trendStatistics(String symbol, String universeName,
                                               int horizonSessions) {
        TradableUniverse universe = parseUniverse(universeName);
        List<Bar> bars = history.bars(symbol, universe);
        if (bars.isEmpty()) {
            return Map.of("error", universe == TradableUniverse.FUTURES
                    ? "Futures market data is a separate Webull entitlement this account does not "
                      + "have. Futures contracts can be listed but not analysed - do not "
                      + "recommend them."
                    : "No price history available for " + symbol + " in the " + universe.label()
                      + " universe.");
        }
        int horizon = horizonSessions > 0 ? horizonSessions : TrendAnalyzer.ONE_MONTH_SESSIONS;
        Optional<TrendStatistics> statistics = TrendAnalyzer.analyse(symbol, bars, horizon,
                List.of(BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
                        BigDecimal.valueOf(5), BigDecimal.valueOf(7), BigDecimal.valueOf(10),
                        BigDecimal.valueOf(15)));
        if (statistics.isEmpty()) {
            return Map.of("error", "Not enough history for " + symbol
                    + " to compute a " + horizon + "-session window.");
        }
        return describe(statistics.get());
    }

    private Map<String, Object> describe(TrendStatistics stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", stats.symbol());
        out.put("lastClose", stats.lastClose());
        out.put("lastSession", stats.lastSession().toString());
        out.put("sessionsAnalysed", stats.sessionsAnalysed());
        out.put("horizonSessions", stats.horizonSessions());
        out.put("historicalWindows", stats.windows());
        out.put("sampleIsSufficient", stats.isSufficient());
        out.put("annualisedVolatilityPercent", stats.annualisedVolatilityPercent());
        out.put("trailingReturnPercent", stats.trailingReturnPercent());
        out.put("medianBestGainInWindowPercent", stats.medianBestGainPercent());
        out.put("medianWorstDrawdownInWindowPercent", stats.medianWorstDrawdownPercent());
        stats.sma20().ifPresent(v -> out.put("sma20", v));
        stats.sma50().ifPresent(v -> out.put("sma50", v));
        stats.high52Week().ifPresent(v -> out.put("high52Week", v));
        stats.low52Week().ifPresent(v -> out.put("low52Week", v));

        List<Map<String, Object>> rates = new ArrayList<>();
        for (TrendStatistics.HitRate rate : stats.hitRates()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("targetGainPercent", rate.targetPercent());
            row.put("hitRatePercent", rate.hitRatePercent());
            row.put("windowsHit", rate.windowsHit());
            rate.medianSessionsToHit().ifPresent(v -> row.put("medianSessionsToHit", v));
            rates.add(row);
        }
        out.put("hitRates", rates);
        out.put("interpretation", "hitRatePercent is the share of overlapping historical windows "
                + "in which the high touched that gain within the horizon - i.e. how often a "
                + "resting sell limit there would have filled. Windows overlap, so they are "
                + "correlated and the effective sample is smaller than the count suggests. This "
                + "describes the past; it is not a forecast.");
        return out;
    }

    /**
     * Ranks every instrument with locally loaded history by how often a given gain was reached.
     *
     * <p>Deliberately restricted to instruments already backfilled - the watchlist - so a scan
     * costs no vendor requests at all. Scanning a whole universe would be thousands of calls
     * against an API that rate-limits after a handful, so anything outside the watchlist is
     * examined one at a time through {@link #trendStatistics}.
     */
    public Map<String, Object> rankCandidates(String universeName, BigDecimal targetGainPercent,
                                             int horizonSessions) {
        TradableUniverse universe = parseUniverse(universeName);
        if (universe != TradableUniverse.EQUITY) {
            // The scan reads locally backfilled history, which only covers watchlist equities.
            // Returning equities to an account that cannot trade them would be worse than
            // returning nothing, so it says what to do instead.
            return Map.of(
                    "error", "Only equities have history loaded locally, so there is nothing to "
                            + "rank for the " + universe.label() + " universe.",
                    "whatToDoInstead", "Use get_tradable_universe to pick candidate symbols, then "
                            + "get_trend_statistics on each one with universe=" + universe.name()
                            + ". Each of those is a separate vendor request, so choose a handful "
                            + "rather than scanning.");
        }
        BigDecimal target = targetGainPercent == null || targetGainPercent.signum() <= 0
                ? BigDecimal.valueOf(3) : targetGainPercent;
        int horizon = horizonSessions > 0 ? horizonSessions : TrendAnalyzer.ONE_MONTH_SESSIONS;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instrument instrument : referenceData.equities()) {
            List<Bar> bars = priceHistory.bars(instrument.id(), AdjustmentPolicy.SPLITS_ONLY);
            if (bars.isEmpty()) {
                continue;
            }
            TrendAnalyzer.analyse(instrument.primarySymbol(), bars, horizon, List.of(target))
                    .ifPresent(stats -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("symbol", instrument.primarySymbol());
                        row.put("name", instrument.name());
                        // Watchlist names carry a live streaming quote; candidates are priced
                        // from their last daily close until one is requested.
                        row.put("watchlisted", referenceData.isWatched(instrument.id()));
                        row.put("lastClose", stats.lastClose());
                        stats.hitRates().stream().findFirst().ifPresent(rate -> {
                            row.put("hitRatePercent", rate.hitRatePercent());
                            rate.medianSessionsToHit()
                                    .ifPresent(v -> row.put("medianSessionsToHit", v));
                        });
                        row.put("medianWorstDrawdownPercent", stats.medianWorstDrawdownPercent());
                        row.put("annualisedVolatilityPercent", stats.annualisedVolatilityPercent());
                        row.put("historicalWindows", stats.windows());
                        marketData.quote(instrument.id()).ifPresent(quote -> {
                            row.put("ask", quote.ask().toDisplay());
                            row.put("spreadBps", quote.spreadBasisPoints());
                            row.put("isLiveMarketData", quote.isLive());
                        });
                        rows.add(row);
                    });
        }
        rows.sort((a, b) -> compareHitRates(b, a));
        return Map.of(
                "targetGainPercent", target,
                "horizonSessions", horizon,
                "candidates", rows,
                "scanned", rows.size(),
                "note", "Ranked by how often that gain was touched within the horizon. Covers "
                        + "every instrument with daily history loaded - the watchlist plus the "
                        + "wider candidate pool. Rows marked watchlisted=false are priced from "
                        + "their last close, so call get_quotes on any you shortlist to get a "
                        + "live bid and ask before setting a target.");
    }

    @SuppressWarnings("unchecked")
    private static int compareHitRates(Map<String, Object> a, Map<String, Object> b) {
        BigDecimal left = (BigDecimal) a.getOrDefault("hitRatePercent", BigDecimal.ZERO);
        BigDecimal right = (BigDecimal) b.getOrDefault("hitRatePercent", BigDecimal.ZERO);
        return left.compareTo(right);
    }

    /** What the selected account may trade, so the model cannot propose an ineligible instrument. */
    public Map<String, Object> tradableUniverse(String environmentName, String accountId,
                                                String query, int limit) {
        TradingEnvironment environment = environmentName == null || environmentName.isBlank()
                ? accounts.defaultEnvironment()
                : TradingEnvironment.parseOrSandbox(environmentName);
        Optional<BrokerAccount> account = accountId == null || accountId.isBlank()
                ? accounts.defaultAccount(environment)
                : accounts.findAccount(environment, accountId);
        TradableUniverse universe = account
                .map(a -> TradableUniverse.forAccountClass(a.accountClass()))
                .orElse(TradableUniverse.EQUITY);

        // Blocking variant: a recommendation run lasts minutes, and reasoning about an empty
        // universe because the catalog was still warming would be worse than waiting for it.
        catalog.instrumentsNow(universe, java.time.Duration.ofSeconds(45));
        InstrumentCatalog.Listing listing = catalog.listing(
                universe, query, "", true, limit > 0 ? Math.min(limit, 100) : 40);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TradableInstrument instrument : listing.instruments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", instrument.symbol());
            row.put("name", instrument.name());
            row.put("group", instrument.group());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        account.ifPresent(a -> {
            out.put("account", a.displayName());
            out.put("accountClass", a.accountClass());
            out.put("accountType", a.accountType());
        });
        out.put("environment", environment.label());
        out.put("universe", universe.name());
        out.put("universeLabel", universe.label());
        out.put("totalInstruments", listing.total());
        out.put("showing", rows.size());
        out.put("instruments", rows);
        if (universe == TradableUniverse.FUTURES) {
            out.put("warning", "This account trades futures, but futures market data is a separate "
                    + "Webull entitlement this account does not have. Contracts can be listed and "
                    + "not analysed, so do not recommend futures trades.");
        }
        return out;
    }

    static TradableUniverse parseUniverse(String name) {
        if (name == null || name.isBlank()) {
            return TradableUniverse.EQUITY;
        }
        try {
            return TradableUniverse.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TradableUniverse.EQUITY;
        }
    }

    /** The current feed state, so the model can qualify how fresh its inputs are. */
    public Map<String, Object> feedStatus() {
        var status = marketData.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeSource", status.activeSource());
        out.put("dataQuality", status.provenance().description());
        out.put("isLiveMarketData", status.provenance().isLive());
        out.put("connectedToVendor", status.connected());
        out.put("summary", status.summary());
        status.lastUpdateAt().ifPresent(t -> out.put("lastUpdateAt", t.toString()));
        return out;
    }

    static AdjustmentPolicy parsePolicy(String name) {
        if (name == null || name.isBlank()) {
            return AdjustmentPolicy.SPLITS_ONLY;
        }
        try {
            return AdjustmentPolicy.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AdjustmentPolicy.SPLITS_ONLY;
        }
    }

    /** Serialises a tool result for the model. */
    String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"Could not serialise result: " + e.getMessage() + "\"}";
        }
    }
}
