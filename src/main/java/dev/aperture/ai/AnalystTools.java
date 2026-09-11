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

    static final List<Tool> ALL = List.of(
            LIST_INSTRUMENTS, GET_QUOTES, GET_PRICE_HISTORY, GET_CORPORATE_ACTIONS,
            COMPARE_ADJUSTMENTS, GET_ACCOUNT_SUMMARY, GET_FEED_STATUS);

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
