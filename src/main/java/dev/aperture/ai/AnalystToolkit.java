package dev.aperture.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aperture.account.AccountBalance;
import dev.aperture.account.AccountPosition;
import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.corporate.RecordedAction;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.PriceHistory;
import dev.aperture.marketdata.Quote;
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
    private final ObjectMapper json;

    public AnalystToolkit(ReferenceDataService referenceData,
                          MarketDataService marketData,
                          PriceHistory priceHistory,
                          CorporateActionService corporateActions,
                          AccountService accounts,
                          ObjectMapper json) {
        this.referenceData = referenceData;
        this.marketData = marketData;
        this.priceHistory = priceHistory;
        this.corporateActions = corporateActions;
        this.accounts = accounts;
        this.json = json;
    }

    /** Every instrument Aperture is following, with whether history is loaded. */
    public Map<String, Object> listInstruments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instrument instrument : referenceData.all()) {
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
        for (String symbol : symbols) {
            Optional<Instrument> instrument = referenceData.resolve(symbol);
            if (instrument.isEmpty()) {
                unknown.add(symbol);
                continue;
            }
            Optional<Quote> quote = marketData.quote(instrument.get().id());
            if (quote.isEmpty()) {
                continue;
            }
            rows.add(quoteRow(instrument.get(), quote.get()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quotes", rows);
        if (!unknown.isEmpty()) {
            out.put("unknownSymbols", unknown);
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
