package dev.aperture.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one analyst run.
 *
 * <p>{@code toolCalls} is part of the result rather than buried in a log: the point of an
 * LLM over live data is that its reasoning is auditable, and "which of my tools did it actually
 * consult" is the first thing worth knowing when an answer looks wrong.
 */
public record AnalysisResult(
        boolean succeeded,
        String answer,
        List<String> toolCalls,
        String model,
        int turns,
        Duration elapsed,
        Instant completedAt,
        String error) {

    public AnalysisResult {
        Objects.requireNonNull(toolCalls, "toolCalls");
        toolCalls = List.copyOf(toolCalls);
    }

    public static AnalysisResult success(String answer, List<String> toolCalls, String model,
                                         int turns, Duration elapsed, Instant completedAt) {
        return new AnalysisResult(true, answer, toolCalls, model, turns, elapsed, completedAt, null);
    }

    public static AnalysisResult failure(String error, List<String> toolCalls, String model,
                                         int turns, Duration elapsed, Instant completedAt) {
        return new AnalysisResult(false, "", toolCalls, model, turns, elapsed, completedAt, error);
    }

    /** A failure that never reached the model - no key, or the feature is switched off. */
    public static AnalysisResult unavailable(String reason, Instant completedAt) {
        return new AnalysisResult(false, "", List.of(), "", 0, Duration.ZERO, completedAt, reason);
    }
}
