package dev.aperture.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * An LLM analyst that answers questions about the live book by calling Aperture's own services.
 *
 * <h2>Why the loop is written by hand</h2>
 *
 * <p>The SDK ships a tool runner that would drive this loop automatically, but it instantiates
 * tool classes reflectively and cannot reach Spring-managed beans. Since the entire point is
 * querying the <em>real</em> market-data and account services rather than a toy tool, the loop is
 * written out: request, execute the tool calls against injected beans, feed the results back,
 * repeat until the model stops asking.
 *
 * <p>The loop is bounded. An unbounded agentic loop against a metered API is a way to spend money
 * by accident.
 */
@Service
public class MarketAnalyst {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalyst.class);

    private static final String SYSTEM_PROMPT = """
            You are the analyst inside Aperture, a live equities market-data console.

            Answer the user's question by calling the tools to look at real data. Do not answer \
            from background knowledge about these companies - the question is what the market is \
            doing now, and your training data is not that.

            Rules that matter here:

            1. CHECK THE DATA SOURCE. Every quote carries a dataSource field and an \
            isLiveMarketData flag. If the data is simulated, say so plainly and do not present it \
            as a market observation.

            2. CORPORATE ACTIONS. Price history comes in three bases: NONE (raw prints), \
            SPLITS_ONLY (the convention for charts) and TOTAL_RETURN (also credits cash \
            dividends). A raw series spanning a split shows a crash that never happened - NVDA's \
            10-for-1 in June 2024 reads as a 90% loss. If a return looks extreme, call \
            compare_adjustments before concluding anything. Always say which basis you used.

            3. BE CONCRETE. Cite the numbers you actually retrieved. A claim with no number \
            behind it is not analysis.

            4. SAY WHAT YOU DO NOT KNOW. If history has not loaded or an account is unreachable, \
            say so rather than reasoning around the gap.

            5. YOU CANNOT TRADE. You have no tool that places, modifies or cancels an order, by \
            design. If asked to trade, say that you analyse and the human places the order.

            Be direct and brief. Lead with the answer, then the evidence.
            """;

    private final AnalystToolkit toolkit;
    private final TradePlanner planner;
    private final AccountService accounts;
    private final MarketDataService marketData;
    private final ApertureProperties.Analyst config;
    private final MarketClock clock;
    private final AnthropicClient client;

    public MarketAnalyst(AnalystToolkit toolkit,
                         TradePlanner planner,
                         AccountService accounts,
                         MarketDataService marketData,
                         ApertureProperties properties,
                         MarketClock clock,
                         @Autowired(required = false) @Nullable AnthropicClient client) {
        this.toolkit = toolkit;
        this.planner = planner;
        this.accounts = accounts;
        this.marketData = marketData;
        this.config = properties.analyst();
        this.clock = clock;
        this.client = client;
    }

    public boolean isAvailable() {
        return client != null;
    }

    public String model() {
        return config.model();
    }

    /** Runs one question to completion. Never throws; failures come back as a failed result. */
    public AnalysisResult analyse(String question) {
        Instant started = clock.now();
        if (client == null) {
            return AnalysisResult.unavailable(
                    "The analyst is not configured. Set ANTHROPIC_API_KEY to enable it.",
                    clock.now());
        }
        if (question == null || question.isBlank()) {
            return AnalysisResult.unavailable("Ask a question.", clock.now());
        }

        List<String> toolCalls = new ArrayList<>();
        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(question)
                .build());

        int turns = 0;
        try {
            while (turns < config.maxToolIterations()) {
                turns++;
                Message response = client.messages().create(paramsFor(conversation));

                if (log.isDebugEnabled()) {
                    log.debug("Turn {}: stopReason={} blocks=[{}]", turns,
                            response.stopReason().map(Object::toString).orElse("none"),
                            response.content().stream()
                                    .map(b -> b.type().toString())
                                    .collect(java.util.stream.Collectors.joining(", ")));
                }

                // StopReason is NOT a Java enum - it is a final class implementing the SDK's
                // own Enum interface, with static constants and a real equals(). Comparing it
                // with == or != compares object identity, which is always false, so the loop
                // silently treated every tool-use turn as a finished answer: the model's
                // preamble text was returned and its tool calls were dropped on the floor.
                // Nothing throws, and the reply reads like a plausible non-answer.
                StopReason stopReason = response.stopReason().orElse(null);

                if (StopReason.REFUSAL.equals(stopReason)) {
                    return AnalysisResult.failure(
                            "The model declined to answer this request.", toolCalls,
                            config.model(), turns,
                            Duration.between(started, clock.now()), clock.now());
                }
                if (!StopReason.TOOL_USE.equals(stopReason)) {
                    return AnalysisResult.success(textOf(response), toolCalls, config.model(),
                            turns, Duration.between(started, clock.now()), clock.now());
                }

                // Echo the assistant turn back verbatim. toParam() preserves thinking blocks,
                // which must be replayed unchanged for the model to continue its own reasoning.
                conversation.add(response.toParam());

                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) {
                        continue;
                    }
                    ToolUseBlock toolUse = block.toolUse().get();
                    toolCalls.add(toolUse.name());
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())
                            .content(execute(toolUse))
                            .build()));
                }
                if (results.isEmpty()) {
                    return AnalysisResult.success(textOf(response), toolCalls, config.model(),
                            turns, Duration.between(started, clock.now()), clock.now());
                }
                // Every tool result goes back in ONE user message. Splitting them across several
                // trains the model to stop making parallel calls.
                conversation.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            }
            return AnalysisResult.failure(
                    "The analyst was still requesting data after " + config.maxToolIterations()
                            + " rounds and was stopped. Try a narrower question.",
                    toolCalls, config.model(), turns,
                    Duration.between(started, clock.now()), clock.now());
        } catch (RuntimeException e) {
            log.warn("Analyst run failed: {}", e.getMessage());
            return AnalysisResult.failure(describe(e), toolCalls, config.model(), turns,
                    Duration.between(started, clock.now()), clock.now());
        }
    }

    private static final String RECOMMENDATION_PROMPT = """
            You are the trade recommender inside Aperture, a live equities console.

            Propose trades in exactly one shape: BUY NOW AT THE MARKET, and rest a \
            GOOD-TIL-CANCELLED SELL LIMIT at a target that nets a profit within roughly a month. \
            No shorts, no options, no averaging in - one entry, one exit.

            How to work:

            1. START WITH rank_candidates. One call ranks everything with loaded history by how \
            often a given gain was actually reached inside the horizon. Checking symbols one at a \
            time costs a vendor request each against a rate-limited API.

            2. CHECK WHAT THE ACCOUNT CAN TRADE with get_tradable_universe before proposing \
            anything. An Events account cannot buy stocks. Never recommend futures: that data is \
            a separate entitlement this account does not have, so no target could be justified.

            3. JUSTIFY THE TARGET FROM THE HIT RATE. get_trend_statistics tells you how often \
            each gain was touched within the horizon. Pick a target with a hit rate you would \
            actually stand behind, and say what it is. A target reached in 20% of past windows is \
            a bad trade however good the story sounds.

            4. RESPECT THE DRAWDOWN. Every window has a worst point. A 5% target that historically \
            required sitting through a 9% drawdown is a different proposition from one that never \
            went more than 2% against you. Say which you are proposing.

            5. SIZE IT. Keep the total cost of all recommendations inside the stated capital, and \
            do not put everything into one name.

            6. BEING SELECTIVE IS THE JOB. Two well-evidenced trades beat six speculative ones. \
            If nothing clears the bar, submit an empty list and say why - that is a valid and \
            often correct answer.

            What NOT to do:

            - Do not state expected profits, percentage returns or reward-to-risk. Aperture \
            computes those from live prices and rejects plans whose target is below the entry or \
            whose gain does not clear the spread. Give the target; the arithmetic is not yours.
            - Do not rely on what you remember about these companies. Your training data is not \
            today's market. Every claim must come from a tool call in this conversation.
            - Do not present simulated data as live. Check the dataSource field.

            Finish by calling submit_recommendations exactly once.
            """;

    /**
     * Runs the recommender.
     *
     * @param capital the most the recommendations may commit in total; falls back to the
     *     account's own buying power when not supplied
     */
    public RecommendationSet recommend(String environmentName, String accountId,
                                       BigDecimal capital) {
        Instant started = clock.now();
        if (client == null) {
            return RecommendationSet.unavailable(
                    "The analyst is not configured. Set ANTHROPIC_API_KEY to enable it.",
                    clock.now());
        }

        TradingEnvironment environment = environmentName == null || environmentName.isBlank()
                ? accounts.defaultEnvironment()
                : TradingEnvironment.parseOrSandbox(environmentName);
        Optional<BrokerAccount> account = accountId == null || accountId.isBlank()
                ? accounts.defaultAccount(environment)
                : accounts.findAccount(environment, accountId);

        TradableUniverse universe = account
                .map(a -> TradableUniverse.forAccountClass(a.accountClass()))
                .orElse(TradableUniverse.EQUITY);

        Money budget = resolveBudget(account, capital);

        List<String> toolCalls = new ArrayList<>();
        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(openingBrief(environment, account, universe, budget))
                .build());

        int turns = 0;
        try {
            while (turns < config.maxToolIterations()) {
                turns++;
                Message response = client.messages().create(
                        paramsFor(conversation, RECOMMENDATION_PROMPT,
                                AnalystTools.FOR_RECOMMENDATIONS));

                StopReason stopReason = response.stopReason().orElse(null);
                if (StopReason.REFUSAL.equals(stopReason)) {
                    return RecommendationSet.failure("The model declined this request.",
                            config.model(), turns, toolCalls,
                            Duration.between(started, clock.now()), clock.now());
                }
                if (!StopReason.TOOL_USE.equals(stopReason)) {
                    // It stopped without submitting. Return what it said rather than nothing.
                    return new RecommendationSet(true, List.of(), textOf(response), Money.zero(),
                            account.map(BrokerAccount::displayName).orElse(""),
                            environment.label(), marketData.status().provenance().isLive(),
                            config.model(), turns, toolCalls,
                            Duration.between(started, clock.now()), clock.now(), null);
                }

                conversation.add(response.toParam());

                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) {
                        continue;
                    }
                    ToolUseBlock toolUse = block.toolUse().get();
                    toolCalls.add(toolUse.name());

                    if ("submit_recommendations".equals(toolUse.name())) {
                        return build(toolUse, universe, environment, account, budget,
                                textOf(response), turns, toolCalls, started);
                    }
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())
                            .content(execute(toolUse))
                            .build()));
                }
                if (results.isEmpty()) {
                    return RecommendationSet.failure(
                            "The model stopped without submitting any recommendations.",
                            config.model(), turns, toolCalls,
                            Duration.between(started, clock.now()), clock.now());
                }
                conversation.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            }
            return RecommendationSet.failure(
                    "The recommender was still gathering data after " + config.maxToolIterations()
                            + " rounds and was stopped.",
                    config.model(), turns, toolCalls,
                    Duration.between(started, clock.now()), clock.now());
        } catch (RuntimeException e) {
            log.warn("Recommendation run failed: {}", e.getMessage());
            return RecommendationSet.failure(describe(e), config.model(), turns, toolCalls,
                    Duration.between(started, clock.now()), clock.now());
        }
    }

    /**
     * Prices every proposal and assembles the result.
     *
     * <p>Each proposal goes through {@link TradePlanner}, which computes the entry, the profit and
     * the historical odds from the data. A proposal naming an unquotable symbol is dropped rather
     * than shown with invented numbers.
     */
    private RecommendationSet build(ToolUseBlock toolUse, TradableUniverse accountUniverse,
                                    TradingEnvironment environment,
                                    Optional<BrokerAccount> account, Money budget,
                                    String commentaryText, int turns, List<String> toolCalls,
                                    Instant started) {
        Map<String, Object> payload = arguments(toolUse);
        String commentary = payload.get("commentary") instanceof String text
                ? text : commentaryText;

        List<TradeRecommendation> planned = new ArrayList<>();
        Money committed = Money.zero();

        Object raw = payload.get("recommendations");
        if (raw instanceof List<?> proposals) {
            for (Object element : proposals) {
                if (!(element instanceof Map<?, ?> proposal)) {
                    continue;
                }
                String symbol = string(proposal.get("symbol"));
                if (symbol == null || symbol.isBlank()) {
                    continue;
                }
                BigDecimal quantity = decimal(proposal.get("quantity"));
                BigDecimal target = decimal(proposal.get("targetPrice"));
                if (quantity == null || quantity.signum() <= 0 || target == null
                        || target.signum() <= 0) {
                    continue;
                }
                TradableUniverse universe = proposal.get("universe") == null
                        ? accountUniverse
                        : AnalystToolkit.parseUniverse(string(proposal.get("universe")));

                planner.plan(symbol, symbol, universe, Quantity.of(quantity), Price.of(target),
                                integer(proposal.get("horizonSessions"), 21),
                                string(proposal.get("conviction")),
                                string(proposal.get("rationale")))
                        .ifPresent(planned::add);
            }
        }
        for (TradeRecommendation recommendation : planned) {
            committed = committed.plus(recommendation.economics().notional());
        }
        if (budget.isPositive() && committed.isGreaterThan(budget)) {
            commentary = commentary + "\n\nNote: these recommendations commit "
                    + committed.toDisplay().toPlainString() + ", which exceeds the "
                    + budget.toDisplay().toPlainString() + " available.";
        }

        return new RecommendationSet(true, planned, commentary, committed,
                account.map(BrokerAccount::displayName).orElse(""), environment.label(),
                marketData.status().provenance().isLive(), config.model(), turns, toolCalls,
                Duration.between(started, clock.now()), clock.now(), null);
    }

    /**
     * How much the recommendations may commit.
     *
     * <p>Every fallback is checked for being <strong>positive</strong>, not merely present. A
     * margin account carrying a debit reports a negative cash balance, and an account the vendor
     * declines to quote buying power for reports none at all - so the obvious
     * "buying power, else cash" chain can hand the model a negative budget, against which every
     * proposal trivially "exceeds available capital". Real accounts look like this: one of the
     * production accounts here has no reported buying power and cash of -2,358.
     *
     * <p>When nothing usable is found this returns zero rather than inventing a figure. The brief
     * then says the capital is unknown, which is the honest input - guessing a round number would
     * put a fabricated constraint in front of the model and size real orders against it.
     */
    // Package-private so the budget rules can be tested directly - the negative-cash case came
    // from a real production account and is worth pinning.
    Money resolveBudget(Optional<BrokerAccount> account, BigDecimal capital) {
        if (capital != null && capital.signum() > 0) {
            return Money.usd(capital);
        }
        return account.map(accounts::balance)
                .flatMap(balance -> balance.buyingPower().filter(Money::isPositive)
                        .or(() -> balance.totalCash().filter(Money::isPositive)))
                .orElse(Money.zero());
    }

    private String openingBrief(TradingEnvironment environment, Optional<BrokerAccount> account,
                                TradableUniverse universe, Money budget) {
        String capitalLine = budget.isPositive()
                ? budget.toDisplay().toPlainString()
                : "UNKNOWN - the broker reports no usable buying power for this account (a margin "
                  + "debit or an unquoted figure). Do not size positions against a guess: say so "
                  + "and recommend nothing until a capital figure is supplied.";
        return """
                Recommend trades for this account.

                Account: %s
                Environment: %s
                Tradable universe: %s
                Capital available: %s
                Today: %s

                Find entries worth taking now, each with a target that history says is reachable                 within about a month. Work from the tools, not from memory.
                """.formatted(
                account.map(BrokerAccount::displayName).orElse("(none selected)"),
                environment.label(),
                universe.label(),
                capitalLine,
                clock.today());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private MessageCreateParams paramsFor(List<MessageParam> conversation) {
        return paramsFor(conversation, SYSTEM_PROMPT, AnalystTools.ALL);
    }

    private MessageCreateParams paramsFor(List<MessageParam> conversation, String systemPrompt,
                                          List<Tool> tools) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .system(systemPrompt)
                // Adaptive thinking: the model decides how much reasoning a question needs. A
                // fixed budget would over-think "what is AAPL trading at" and under-think a
                // multi-instrument comparison.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .build());
        for (Tool tool : tools) {
            builder.addTool(tool);
        }
        for (MessageParam message : conversation) {
            builder.addMessage(message);
        }
        return builder.build();
    }

    /**
     * Dispatches one tool call.
     *
     * <p>An unknown tool name or a bad argument comes back as a JSON error rather than an
     * exception, so the model can correct itself on the next turn instead of the whole run
     * failing.
     */
    private String execute(ToolUseBlock toolUse) {
        try {
            Map<String, Object> args = arguments(toolUse);
            Object result = switch (toolUse.name()) {
                case "list_instruments" -> toolkit.listInstruments();
                case "get_quotes" -> toolkit.quotes(stringList(args.get("symbols")));
                case "get_price_history" -> toolkit.priceHistory(
                        string(args.get("symbol")),
                        string(args.get("policy")),
                        integer(args.get("days"), 60));
                case "get_corporate_actions" -> toolkit.corporateActions(string(args.get("symbol")));
                case "compare_adjustments" -> toolkit.compareAdjustments(string(args.get("symbol")));
                case "get_account_summary" -> toolkit.accountSummary(string(args.get("environment")));
                case "get_feed_status" -> toolkit.feedStatus();
                case "get_trend_statistics" -> toolkit.trendStatistics(
                        string(args.get("symbol")),
                        string(args.get("universe")),
                        integer(args.get("horizonSessions"), 21));
                case "rank_candidates" -> toolkit.rankCandidates(
                        string(args.get("universe")),
                        decimal(args.get("targetGainPercent")),
                        integer(args.get("horizonSessions"), 21));
                case "get_tradable_universe" -> toolkit.tradableUniverse(
                        string(args.get("environment")),
                        string(args.get("accountId")),
                        string(args.get("query")),
                        integer(args.get("limit"), 40));
                default -> Map.of("error", "Unknown tool: " + toolUse.name());
            };
            return toolkit.toJson(result);
        } catch (RuntimeException e) {
            log.debug("Tool {} failed: {}", toolUse.name(), e.getMessage());
            return toolkit.toJson(Map.of("error",
                    "Tool " + toolUse.name() + " failed: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(ToolUseBlock toolUse) {
        Map<String, Object> args = toolUse._input().convert(Map.class);
        return args == null ? Map.of() : args;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String single) {
            return List.of(single);
        }
        return List.of();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String textOf(Message response) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        return text.toString().trim();
    }

    /** Turns SDK exceptions into something a user can act on. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        if (message.contains("authentication") || message.contains("401")) {
            return "Anthropic rejected the API key.";
        }
        if (message.contains("rate_limit") || message.contains("429")) {
            return "Anthropic rate limit reached. Try again shortly.";
        }
        return message;
    }
}
