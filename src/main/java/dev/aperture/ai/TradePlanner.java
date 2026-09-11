package dev.aperture.ai;

import dev.aperture.analysis.HistoryProvider;
import dev.aperture.analysis.TrendAnalyzer;
import dev.aperture.analysis.TrendStatistics;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.Quote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Turns a proposed trade into checked arithmetic.
 *
 * <p>The model chooses a symbol, a size and a target. Everything that follows - the entry price,
 * the profit, the historical odds, whether the plan makes sense at all - is computed here from
 * live prices and the instrument's own bars.
 *
 * <p>That division is the whole design. Asked for an expected profit a language model will supply
 * a confident figure that nothing checks. Asked only for a target, its proposal can be priced,
 * tested against history and rejected. The checks below are ones the model does not reliably run
 * on itself: that the target is actually above the entry, that the gain clears the spread it must
 * cross to get in, and that the target has ever historically been reached in the time allowed.
 */
@Service
public class TradePlanner {

    /** Below this historical hit rate, a target is flagged rather than silently accepted. */
    private static final BigDecimal WEAK_HIT_RATE = BigDecimal.valueOf(25);

    private final MarketDataService marketData;
    private final HistoryProvider history;

    public TradePlanner(MarketDataService marketData, HistoryProvider history) {
        this.marketData = marketData;
        this.history = history;
    }

    /**
     * Prices and checks one proposal.
     *
     * @param targetPrice the resting sell limit the model proposes
     * @return empty only when there is no usable quote at all, in which case there is nothing to
     *     price the trade against
     */
    public Optional<TradeRecommendation> plan(String symbol, String name,
                                              TradableUniverse universe, Quantity quantity,
                                              Price targetPrice, int horizonSessions,
                                              String conviction, String rationale) {
        // On demand, not from the cache: a shortlisted candidate is not streamed, and pricing
        // an entry from yesterday's close when a live ask is one request away would understate
        // or overstate every figure that follows from it.
        Optional<Quote> quote = marketData.quoteOnDemand(symbol);
        List<Bar> bars = history.bars(symbol, universe);

        // Fall back to the last bar's close when there is no live quote - common for instruments
        // outside the streaming watchlist.
        Price entry = quote.map(TradePlanner::entryPriceOf)
                .orElseGet(() -> bars.isEmpty() ? null : bars.get(bars.size() - 1).close());
        if (entry == null || !entry.isPositive()) {
            return Optional.empty();
        }

        List<String> warnings = new ArrayList<>();
        boolean viable = true;

        // --- the arithmetic ---
        Money notional = entry.times(quantity);
        Money grossProfit = Money.usd(
                targetPrice.value().subtract(entry.value()).multiply(quantity.value()));
        BigDecimal returnPercent = percent(entry.value(), targetPrice.value());

        BigDecimal spreadCostPercent = quote
                .filter(Quote::hasTwoSidedMarket)
                .map(q -> q.spreadBasisPoints().divide(BigDecimal.valueOf(100), 4,
                        RoundingMode.HALF_EVEN))
                .orElse(BigDecimal.ZERO);
        BigDecimal netReturn = returnPercent.subtract(spreadCostPercent);

        // --- checks the model does not run on itself ---
        if (targetPrice.compareTo(entry) <= 0) {
            warnings.add("The target " + targetPrice.toDisplay().toPlainString()
                    + " is at or below the entry price " + entry.toDisplay().toPlainString()
                    + ", so this plan cannot produce a profit.");
            viable = false;
        } else if (netReturn.signum() <= 0) {
            warnings.add("The target gain of " + returnPercent.setScale(2, RoundingMode.HALF_EVEN)
                    + "% is smaller than the " + spreadCostPercent.setScale(2, RoundingMode.HALF_EVEN)
                    + "% cost of crossing the spread to get in.");
            viable = false;
        }

        quote.filter(q -> !q.isLive()).ifPresent(q -> warnings.add(
                "Priced from simulated market data, not a live quote."));

        // --- what the instrument's own history says about this exact target ---
        Optional<TrendStatistics> statistics = bars.isEmpty()
                ? Optional.empty()
                : TrendAnalyzer.analyse(symbol, bars, horizonSessions,
                        List.of(returnPercent.setScale(2, RoundingMode.HALF_EVEN)));

        Optional<BigDecimal> hitRate = Optional.empty();
        Optional<Integer> windows = Optional.empty();
        Optional<Integer> sessionsToHit = Optional.empty();
        BigDecimal medianDrawdown = BigDecimal.ZERO;

        if (statistics.isPresent()) {
            TrendStatistics stats = statistics.get();
            medianDrawdown = stats.medianWorstDrawdownPercent();
            windows = Optional.of(stats.windows());
            Optional<TrendStatistics.HitRate> rate = stats.hitRates().stream().findFirst();
            if (rate.isPresent()) {
                hitRate = Optional.of(rate.get().hitRatePercent());
                sessionsToHit = rate.get().medianSessionsToHit();
                if (!stats.isSufficient()) {
                    warnings.add("Only " + stats.windows() + " historical windows were available, "
                            + "which is too few to quote odds from with confidence.");
                } else if (rate.get().hitRatePercent().compareTo(WEAK_HIT_RATE) < 0) {
                    warnings.add("This target was reached within the horizon in only "
                            + rate.get().hitRatePercent() + "% of the last " + stats.windows()
                            + " historical windows.");
                }
            }
        } else {
            warnings.add(universe == TradableUniverse.FUTURES
                    ? "Futures market data is a separate Webull entitlement that this account does "
                      + "not have, so no historical odds could be computed for this contract."
                    : "No price history is available for " + symbol
                      + ", so the historical hit rate could not be computed.");
        }

        Optional<BigDecimal> rewardToRisk = medianDrawdown.signum() < 0
                ? Optional.of(returnPercent.divide(medianDrawdown.abs(), 2, RoundingMode.HALF_EVEN))
                : Optional.empty();

        TradeEconomics economics = new TradeEconomics(
                entry, targetPrice, notional, grossProfit,
                returnPercent.setScale(2, RoundingMode.HALF_EVEN),
                spreadCostPercent.setScale(2, RoundingMode.HALF_EVEN),
                netReturn.setScale(2, RoundingMode.HALF_EVEN),
                hitRate, windows, sessionsToHit,
                medianDrawdown.setScale(2, RoundingMode.HALF_EVEN),
                rewardToRisk, warnings, viable);

        List<OrderLeg> legs = List.of(
                OrderLeg.marketBuy(quantity, "Enter now at the market"),
                OrderLeg.gtcSellLimit(quantity, targetPrice,
                        "Rest until the target is reached, good till cancelled"));

        return Optional.of(new TradeRecommendation(
                symbol, name, universe.name(), conviction, rationale,
                horizonSessions, legs, economics));
    }

    /** A market buy lifts the offer, so the ask is the honest entry, not the last print. */
    private static Price entryPriceOf(Quote quote) {
        return quote.ask().isPositive() ? quote.ask() : quote.last();
    }

    private static BigDecimal percent(BigDecimal from, BigDecimal to) {
        if (from.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return to.subtract(from).multiply(BigDecimal.valueOf(100))
                .divide(from, 4, RoundingMode.HALF_EVEN);
    }
}
