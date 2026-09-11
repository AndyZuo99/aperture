package dev.aperture.ai;

import java.util.List;
import java.util.Objects;

/**
 * One proposed trade: a market entry now and a resting exit at a target.
 *
 * <p>Split deliberately into what the model decided and what Aperture derived. The model supplies
 * {@code symbol}, {@code quantity}, {@code targetPrice}, {@code horizonSessions} and the
 * reasoning; {@link #economics()} is computed from live prices and the instrument's own history.
 * Keeping the boundary visible is the point - a reader can see exactly which numbers came from a
 * language model and which came from the data.
 *
 * @param conviction the model's own confidence, carried as stated and never used in any
 *     calculation
 */
public record TradeRecommendation(
        String symbol,
        String name,
        String universe,
        String conviction,
        String rationale,
        int horizonSessions,
        List<OrderLeg> legs,
        TradeEconomics economics) {

    public TradeRecommendation {
        Objects.requireNonNull(symbol, "symbol");
        symbol = symbol.toUpperCase();
        name = name == null ? symbol : name;
        universe = universe == null ? "" : universe;
        conviction = conviction == null ? "" : conviction;
        rationale = rationale == null ? "" : rationale;
        legs = List.copyOf(legs);
    }

    /** Roughly how long the horizon is in calendar terms, for display. */
    public String horizonDescription() {
        if (horizonSessions <= 5) {
            return horizonSessions + " sessions (about a week)";
        }
        int weeks = Math.round(horizonSessions / 5f);
        return horizonSessions + " sessions (about " + weeks + " week" + (weeks == 1 ? "" : "s")
                + ")";
    }
}
