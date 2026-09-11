package dev.aperture.ai;

import dev.aperture.common.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A complete run of the analyst: the trades it proposes, plus how it got there.
 *
 * <p>{@code toolCalls} and {@code turns} are part of the result rather than log noise. The whole
 * value of an LLM over live data is that its working is inspectable, and "which data did it
 * actually look at before recommending this" is the first question worth asking of any
 * recommendation.
 */
public record RecommendationSet(
        boolean succeeded,
        List<TradeRecommendation> recommendations,
        String commentary,
        Money totalCapitalRequired,
        String accountLabel,
        String environment,
        boolean marketDataLive,
        String model,
        int turns,
        List<String> toolCalls,
        Duration elapsed,
        Instant completedAt,
        String error) {

    public RecommendationSet {
        Objects.requireNonNull(completedAt, "completedAt");
        recommendations = List.copyOf(recommendations);
        toolCalls = List.copyOf(toolCalls);
        commentary = commentary == null ? "" : commentary;
    }

    public static RecommendationSet failure(String error, String model, int turns,
                                            List<String> toolCalls, Duration elapsed,
                                            Instant completedAt) {
        return new RecommendationSet(false, List.of(), "", Money.zero(), "", "", false,
                model, turns, toolCalls, elapsed, completedAt, error);
    }

    public static RecommendationSet unavailable(String reason, Instant completedAt) {
        return new RecommendationSet(false, List.of(), "", Money.zero(), "", "", false,
                "", 0, List.of(), Duration.ZERO, completedAt, reason);
    }

    public boolean isEmpty() {
        return recommendations.isEmpty();
    }
}
