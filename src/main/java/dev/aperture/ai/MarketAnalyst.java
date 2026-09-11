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
import dev.aperture.config.ApertureProperties;
import dev.aperture.time.MarketClock;
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
    private final ApertureProperties.Analyst config;
    private final MarketClock clock;
    private final AnthropicClient client;

    public MarketAnalyst(AnalystToolkit toolkit,
                         ApertureProperties properties,
                         MarketClock clock,
                         @Autowired(required = false) @Nullable AnthropicClient client) {
        this.toolkit = toolkit;
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

    private MessageCreateParams paramsFor(List<MessageParam> conversation) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .system(SYSTEM_PROMPT)
                // Adaptive thinking: the model decides how much reasoning a question needs. A
                // fixed budget would over-think "what is AAPL trading at" and under-think a
                // multi-instrument comparison.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .build());
        for (Tool tool : AnalystTools.ALL) {
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
