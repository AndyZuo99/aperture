package dev.aperture.web;

import dev.aperture.ai.MarketAnalyst;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The LLM analyst. */
@RestController
@RequestMapping("/api/analyst")
public class AnalystController {

    private final MarketAnalyst analyst;
    private final ApiMapper mapper;

    public AnalystController(MarketAnalyst analyst, ApiMapper mapper) {
        this.analyst = analyst;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "available", analyst.isAvailable(),
                "model", analyst.model(),
                "reason", analyst.isAvailable() ? ""
                        : "Set ANTHROPIC_API_KEY to enable the analyst.");
    }

    /**
     * Asks a question.
     *
     * <p>Synchronous. A tool-using run takes seconds, not minutes, and a streaming or job-polling
     * interface would be more machinery than one text box justifies.
     */
    @PostMapping("/ask")
    public ApiDtos.AnalysisView ask(@RequestBody ApiDtos.AskRequest request) {
        return mapper.toAnalysisView(analyst.analyse(request.question()));
    }

    /**
     * Asks for trade recommendations: a market entry now and a resting GTC exit at a target.
     *
     * <p>Recommendations only. Nothing here submits an order - the toolkit the model reaches has
     * no mutating method at all, and there is no order-submission path in the application.
     *
     * <p>Slower than a question, since the model works through several rounds of data before it
     * commits to anything. Still synchronous: a job queue would be more machinery than one button
     * justifies.
     */
    @PostMapping("/recommend")
    public ApiDtos.RecommendationSetView recommend(
            @RequestBody(required = false) ApiDtos.RecommendRequest request) {
        ApiDtos.RecommendRequest safe = request == null
                ? new ApiDtos.RecommendRequest(null, null, null) : request;
        return mapper.toRecommendationSetView(
                analyst.recommend(safe.environment(), safe.accountId(), safe.capital()));
    }
}
