package dev.aperture.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool schemas the analyst is given.
 *
 * <p>Descriptions are written for the model, not for a developer. Each says when to reach for the
 * tool and what the result means, because a tool the model does not understand the purpose of is
 * a tool it calls at the wrong moment - and the corporate-action tools in particular only help if
 * the model knows that an extreme return is a reason to suspect a split.
 */
final class AnalystTools {

    private AnalystTools() {
    }

    static final Tool LIST_INSTRUMENTS = Tool.builder()
            .name("list_instruments")
            .description("""
                    List every instrument Aperture is following, with its name, type and whether \
                    daily price history has loaded. Call this first if you are unsure which \
                    symbols are available.""")
            .inputSchema(schema(Map.of(), List.of()))
            .build();

    static final Tool GET_QUOTES = Tool.builder()
            .name("get_quotes")
            .description("""
                    Current Level 1 quotes: last price, bid and ask with sizes, spread in basis \
                    points, session volume and change from the previous close. Each quote \
                    includes a dataSource field and an isLiveMarketData flag - check them, and \
                    say so in your answer if the data is simulated rather than live.""")
            .inputSchema(schema(
                    Map.of("symbols", arrayOf("string", "Ticker symbols, e.g. [\"AAPL\",\"NVDA\"]")),
                    List.of("symbols")))
            .build();

    static final Tool GET_PRICE_HISTORY = Tool.builder()
            .name("get_price_history")
            .description("""
                    Daily OHLCV bars, restated according to an adjustment policy. \
                    policy=NONE gives raw prints as traded; SPLITS_ONLY removes split \
                    discontinuities and is the normal choice for a chart; TOTAL_RETURN also \
                    credits cash dividends and is the right basis for comparing performance. \
                    Also returns the window return and annualised volatility. Always state which \
                    policy you used.""")
            .inputSchema(schema(
                    Map.of(
                            "symbol", stringOf("Ticker symbol"),
                            "policy", enumOf(List.of("NONE", "SPLITS_ONLY", "TOTAL_RETURN"),
                                    "Adjustment basis. Defaults to SPLITS_ONLY."),
                            "days", intOf("How many recent sessions to return. Defaults to 60.")),
                    List.of("symbol")))
            .build();

    static final Tool GET_CORPORATE_ACTIONS = Tool.builder()
            .name("get_corporate_actions")
            .description("""
                    Recorded splits and cash dividends with their ex-dates, and the source of \
                    each (Vendor means reported by Webull; Reference means Aperture's built-in \
                    table of known historical splits; Declared means entered by hand). Omit \
                    symbol to list every recorded action.""")
            .inputSchema(schema(
                    Map.of("symbol", stringOf("Ticker symbol. Omit for all instruments.")),
                    List.of()))
            .build();

    static final Tool COMPARE_ADJUSTMENTS = Tool.builder()
            .name("compare_adjustments")
            .description("""
                    Compute the same window return three ways - unadjusted, split-adjusted and \
                    total-return - and list the corporate actions falling inside it. Use this \
                    whenever a return looks implausibly large or negative: a raw series spanning \
                    a 10-for-1 split shows a 90% loss that never happened. This tool tells you \
                    whether a move is real or an artefact.""")
            .inputSchema(schema(Map.of("symbol", stringOf("Ticker symbol")), List.of("symbol")))
            .build();

    static final Tool GET_ACCOUNT_SUMMARY = Tool.builder()
            .name("get_account_summary")
            .description("""
                    Balances and open positions for the account holder's Webull accounts in one \
                    environment, as the broker reports them: net liquidation value, cash, buying \
                    power, and each position's quantity, cost price and unrealised P&L.""")
            .inputSchema(schema(
                    Map.of("environment", enumOf(List.of("PRODUCTION", "SANDBOX"),
                            "Which environment. Defaults to the configured one.")),
                    List.of()))
            .build();

    static final Tool GET_FEED_STATUS = Tool.builder()
            .name("get_feed_status")
            .description("""
                    Whether market data is live or simulated, whether the vendor connection is \
                    up, and when data last arrived. Call this if you need to qualify how much \
                    weight your answer can carry.""")
            .inputSchema(schema(Map.of(), List.of()))
            .build();

    static final Tool GET_TREND_STATISTICS = Tool.builder()
            .name("get_trend_statistics")
            .description("""
                    How often this instrument historically reached a range of gains within a \
                    horizon. For every overlapping past window it reports the share in which the \
                    high TOUCHED each target - which is exactly what fills a resting sell limit. \
                    Also returns the median best gain and the median worst drawdown endured \
                    inside the window, moving averages and the 52-week range. This is the \
                    evidence for any claim that a target is reachable in the time allowed. It \
                    describes the past and is not a forecast.""")
            .inputSchema(schema(
                    Map.of(
                            "symbol", stringOf("Ticker or contract symbol"),
                            "universe", enumOf(List.of("EQUITY", "CRYPTO", "EVENT", "FUTURES"),
                                    "Asset class. Defaults to EQUITY."),
                            "horizonSessions", intOf(
                                    "Forward window in trading sessions. 21 is about a month.")),
                    List.of("symbol")))
            .build();

    static final Tool RANK_CANDIDATES = Tool.builder()
            .name("rank_candidates")
            .description("""
                    Rank every instrument with history already loaded by how often a given gain \
                    was reached within a horizon, with the drawdown endured, volatility, current \
                    ask and spread. Start here: it is one call and costs no vendor requests, \
                    where checking symbols individually costs one each against a rate-limited \
                    API.""")
            .inputSchema(schema(
                    Map.of(
                            "universe", enumOf(List.of("EQUITY", "CRYPTO", "EVENT"),
                                    "Asset class. Only EQUITY has locally loaded history; the "
                                    + "others will tell you to use get_trend_statistics instead."),
                            "targetGainPercent", numberOf(
                                    "Gain to test, in percent. Defaults to 3."),
                            "horizonSessions", intOf(
                                    "Forward window in sessions. Defaults to 21.")),
                    List.of()))
            .build();

    static final Tool GET_TRADABLE_UNIVERSE = Tool.builder()
            .name("get_tradable_universe")
            .description("""
                    What the selected account is actually allowed to trade. An Events account \
                    trades event contracts and only those; a Futures account trades futures. \
                    Check this before proposing anything, because a recommendation the account \
                    cannot execute is worthless.""")
            .inputSchema(schema(
                    Map.of(
                            "environment", enumOf(List.of("PRODUCTION", "SANDBOX"),
                                    "Defaults to the configured environment."),
                            "accountId", stringOf("Account id. Defaults to the first account."),
                            "query", stringOf("Optional symbol or name filter."),
                            "limit", intOf("Maximum instruments to list. Defaults to 40.")),
                    List.of()))
            .build();

    /**
     * The structured hand-off that ends a recommendation run.
     *
     * <p>A tool call rather than a structured-output response so it composes with the multi-turn
     * loop, and so a malformed payload can be handed back for the model to correct rather than
     * failing the whole run.
     *
     * <p>Note what it does <em>not</em> ask for: no expected profit, no percentage return, no
     * reward-to-risk. Those are computed by Aperture from live prices and the instrument's own
     * history. The model supplies only what it is genuinely deciding - which instrument, how much,
     * and the target - so its numbers cannot contradict the data.
     */
    static final Tool SUBMIT_RECOMMENDATIONS = Tool.builder()
            .name("submit_recommendations")
            .description("""
                    Submit the final trade recommendations. Each one becomes a market buy now \
                    plus a good-til-cancelled sell limit at your target. For an event contract \
                    you must also name the side (eventOutcome YES or NO) - the two sides are \
                    separately priced instruments on the same market. Supply only the symbol, \
                    the quantity, the target price and the horizon - Aperture computes the entry \
                    price, the profit, the historical hit rate for your exact target and the \
                    reward-to-risk, and will flag any plan whose target is unreachable or whose \
                    gain does not clear the spread. Call this exactly once, at the end.""")
            .inputSchema(schema(
                    Map.of(
                            "commentary", stringOf(
                                    "A short overview: the shared thesis, and anything you "
                                    + "deliberately did not recommend and why."),
                            "recommendations", arrayOfObjects(
                                    "The proposed trades, best first.")),
                    List.of("recommendations")))
            .build();

    static final List<Tool> ALL = List.of(
            LIST_INSTRUMENTS, GET_QUOTES, GET_PRICE_HISTORY, GET_CORPORATE_ACTIONS,
            COMPARE_ADJUSTMENTS, GET_ACCOUNT_SUMMARY, GET_FEED_STATUS,
            GET_TREND_STATISTICS, RANK_CANDIDATES, GET_TRADABLE_UNIVERSE);

    /** The recommender's tools: everything above, plus the structured hand-off. */
    static final List<Tool> FOR_RECOMMENDATIONS = java.util.stream.Stream
            .concat(ALL.stream(), java.util.stream.Stream.of(SUBMIT_RECOMMENDATIONS))
            .toList();

    private static Tool.InputSchema schema(Map<String, JsonValue> properties,
                                           List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach(props::putAdditionalProperty);
        return Tool.InputSchema.builder()
                .properties(props.build())
                .required(required)
                .build();
    }

    private static JsonValue stringOf(String description) {
        return JsonValue.from(Map.of("type", "string", "description", description));
    }

    private static JsonValue intOf(String description) {
        return JsonValue.from(Map.of("type", "integer", "description", description));
    }

    private static JsonValue numberOf(String description) {
        return JsonValue.from(Map.of("type", "number", "description", description));
    }

    /**
     * The recommendation array.
     *
     * <p>Spelled out field by field because the model has to get these exactly right for the plan
     * to be priceable, and a vague schema produces vague payloads.
     */
    private static JsonValue arrayOfObjects(String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("symbol", Map.of("type", "string",
                "description", "Exact ticker or contract symbol."));
        properties.put("universe", Map.of("type", "string",
                "enum", List.of("EQUITY", "CRYPTO", "EVENT"),
                "description", "Asset class. Futures cannot be analysed on this entitlement."));
        properties.put("quantity", Map.of("type", "number",
                "description", "Number of shares, coins or contracts to buy."));
        properties.put("targetPrice", Map.of("type", "number",
                "description", "The GTC sell limit price. Must be above the current ask."));
        properties.put("horizonSessions", Map.of("type", "integer",
                "description", "Trading sessions to allow. 21 is about a month."));
        properties.put("eventOutcome", Map.of("type", "string",
                "enum", List.of("YES", "NO"),
                "description", "REQUIRED for event contracts, ignored otherwise. An event market "
                        + "trades as two separate instruments at different prices - YES might be "
                        + "0.02 while NO is 0.99 on the same question - so state which side you "
                        + "are buying. Check the quote for the side you choose; do not assume the "
                        + "quoted price is YES."));
        properties.put("conviction", Map.of("type", "string",
                "enum", List.of("HIGH", "MEDIUM", "LOW"),
                "description", "Your confidence in this specific plan."));
        properties.put("rationale", Map.of("type", "string",
                "description", "Why this instrument and this target, citing the hit rate, the "
                        + "drawdown and the trend figures you actually retrieved."));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", properties);
        item.put("required", List.of("symbol", "quantity", "targetPrice", "horizonSessions",
                "rationale"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", item);
        schema.put("description", description);
        return JsonValue.from(schema);
    }

    private static JsonValue enumOf(List<String> values, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        schema.put("description", description);
        return JsonValue.from(schema);
    }

    private static JsonValue arrayOf(String itemType, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", itemType));
        schema.put("description", description);
        return JsonValue.from(schema);
    }
}
