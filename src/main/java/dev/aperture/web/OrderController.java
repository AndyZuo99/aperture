package dev.aperture.web;

import dev.aperture.account.AccountService;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.ai.TradePlanner;
import dev.aperture.ai.TradeRecommendation;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.trading.OrderSubmissionService;
import dev.aperture.trading.SubmissionResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submits a recommendation's orders, on a human's instruction.
 *
 * <p>The endpoint takes the plan's <em>inputs</em> - symbol, quantity, target - and never its
 * computed economics. Everything is re-priced and re-validated here before an order is built, so
 * the numbers that authorise a submission are the ones the server derives at that moment, not
 * whatever a browser happened to be displaying.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /**
     * How far the live ask may have drifted from what the operator saw.
     *
     * <p>The entry is a market order, so it executes at whatever the book offers. Someone who
     * reviewed a plan at 261 and clicks submit after a gap to 275 has not agreed to that trade,
     * and a market order gives them no protection from it.
     */
    private static final BigDecimal MAX_ENTRY_DRIFT_PERCENT = BigDecimal.valueOf(1.0);

    private final TradePlanner planner;
    private final OrderSubmissionService submissions;
    private final AccountService accounts;

    public OrderController(TradePlanner planner, OrderSubmissionService submissions,
                           AccountService accounts) {
        this.planner = planner;
        this.submissions = submissions;
        this.accounts = accounts;
    }

    /** Whether submission is currently possible, and to where. */
    @GetMapping("/capability")
    public java.util.Map<String, Object> capability() {
        return java.util.Map.of(
                "sandboxSubmission", true,
                "productionSubmission", accounts.liveOrdersPermitted(),
                "productionBlockReason", accounts.liveOrderBlockReason(),
                "note", "Sandbox orders are simulated. Production submission requires both "
                        + "aperture.webull.allow-live-trading and the confirmation phrase.");
    }

    @PostMapping("/submit")
    public ApiDtos.SubmissionView submit(@RequestBody ApiDtos.SubmitOrderRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isBlank()) {
            throw new IllegalArgumentException("A symbol is required.");
        }
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            throw new IllegalArgumentException("A positive quantity is required.");
        }
        if (request.targetPrice() == null || request.targetPrice().signum() <= 0) {
            throw new IllegalArgumentException("A positive target price is required.");
        }

        TradingEnvironment environment = TradingEnvironment.parseOrSandbox(request.environment());
        String accountId = request.accountId() == null || request.accountId().isBlank()
                ? accounts.defaultAccount(environment).map(a -> a.accountId()).orElse(null)
                : request.accountId();
        if (accountId == null) {
            return toView(SubmissionResult.refused(
                    "No account selected in " + environment.label() + ".",
                    environment.name(), ""));
        }

        // Re-plan against live prices. This recomputes the entry, the profit and the viability
        // checks rather than trusting anything the client sent.
        Optional<TradeRecommendation> replanned = planner.plan(
                request.symbol(),
                request.symbol(),
                parseUniverse(request.universe()),
                Quantity.of(request.quantity()),
                Price.of(request.targetPrice()),
                request.horizonSessions() == null ? 21 : request.horizonSessions(),
                "",
                "Submitted from a recommendation");

        if (replanned.isEmpty()) {
            return toView(SubmissionResult.refused(
                    "No live price is available for " + request.symbol().toUpperCase()
                            + ", so the order cannot be priced or checked.",
                    environment.name(), ""));
        }
        TradeRecommendation recommendation = replanned.get();

        Optional<String> drift = entryDrift(request.expectedEntryPrice(),
                recommendation.economics().entryPrice().value());
        if (drift.isPresent()) {
            return toView(SubmissionResult.refused(drift.get(), environment.name(), ""));
        }

        return toView(submissions.submit(environment, accountId, recommendation,
                Boolean.TRUE.equals(request.allowExtendedHours())));
    }

    /** Refuses when the market has moved away from the price the operator reviewed. */
    private static Optional<String> entryDrift(BigDecimal expected, BigDecimal actual) {
        if (expected == null || expected.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal movePercent = actual.subtract(expected).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(expected, 4, RoundingMode.HALF_EVEN);
        if (movePercent.compareTo(MAX_ENTRY_DRIFT_PERCENT) <= 0) {
            return Optional.empty();
        }
        return Optional.of("The price has moved "
                + movePercent.setScale(2, RoundingMode.HALF_EVEN) + "% since this plan was shown ("
                + expected.setScale(2, RoundingMode.HALF_EVEN) + " to "
                + actual.setScale(2, RoundingMode.HALF_EVEN)
                + "). A market order would fill at the new price, so nothing was submitted - "
                + "re-run the recommender to price it again.");
    }

    private static TradableUniverse parseUniverse(String value) {
        if (value == null || value.isBlank()) {
            return TradableUniverse.EQUITY;
        }
        try {
            return TradableUniverse.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TradableUniverse.EQUITY;
        }
    }

    private static ApiDtos.SubmissionView toView(SubmissionResult result) {
        return new ApiDtos.SubmissionView(
                result.accepted(),
                result.refusedReason(),
                result.environment(),
                result.accountLabel(),
                result.isUnprotected(),
                result.entry().map(OrderController::toLegView).orElse(null),
                result.exit().map(OrderController::toLegView).orElse(null),
                result.notes());
    }

    private static ApiDtos.LegResultView toLegView(SubmissionResult.LegResult leg) {
        return new ApiDtos.LegResultView(
                leg.side(), leg.orderType(), leg.timeInForce(), leg.quantity(),
                leg.limitPrice().orElse(null), leg.clientOrderId(), leg.orderId().orElse(null),
                leg.status(), leg.filledQuantity().orElse(null), leg.filledPrice().orElse(null),
                leg.error().orElse(null));
    }
}
