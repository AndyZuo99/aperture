package dev.aperture.corporate;

import dev.aperture.common.Price;
import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Restates a historical price series so that returns across a corporate action are real returns
 * rather than artefacts.
 *
 * <h2>Back-adjustment</h2>
 *
 * <p>The most recent bar is left alone and history is restated into today's terms. The
 * alternative - keeping old bars at their original values and inflating recent ones - means
 * today's chart disagrees with today's quote.
 *
 * <h2>The boundary convention</h2>
 *
 * <p>The ex-date is the first session trading <em>without</em> the entitlement, so a bar dated on
 * the ex-date is already in post-action terms. Only bars <strong>strictly before</strong> it are
 * adjusted. Getting this off by one leaves a one-session discontinuity small enough to survive
 * review and large enough to corrupt every return that crosses it.
 *
 * <h2>Only the difference is applied</h2>
 *
 * <p>The input carries a {@link PriceBasis} saying what it has already been restated for, and only
 * the gap between that and the requested policy is applied. This is not a refinement - it is the
 * difference between working and catastrophically wrong. Webull's bars arrive split-adjusted, so
 * an engine that applied splits unconditionally would divide a post-split history by ten all over
 * again.
 *
 * <h2>Factors come from the raw series</h2>
 *
 * <p>A cash dividend's factor is proportional to the close it was paid out of. Computing factors
 * against a partially-adjusted series would make the result depend on the order actions were
 * processed in, so every factor is derived up-front from the incoming closes.
 */
public final class PriceAdjuster {

    private PriceAdjuster() {
    }

    /**
     * Restates {@code bars} to the basis {@code policy} asks for, applying only what is missing.
     *
     * @param bars the series in any order; all bars are assumed to share the basis of the first
     * @return the series plus the basis actually delivered, which may not be the one requested
     */
    public static AdjustedSeries adjust(List<Bar> bars, List<CorporateAction> actions,
                                        AdjustmentPolicy policy) {
        if (bars.isEmpty()) {
            return AdjustedSeries.empty(policy);
        }
        List<Bar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing(Bar::sessionDate));

        PriceBasis sourceBasis = sorted.get(0).basis();
        PriceBasis target = PriceBasis.forPolicy(policy);

        // Removing an adjustment the provider already applied would need a complete list of the
        // actions they used. Aperture's split table is curated, not exhaustive, so it declines
        // rather than producing confident-looking wrong prices.
        if (sourceBasis.wouldRequireUnadjusting(target)) {
            return new AdjustedSeries(sorted, policy, sourceBasis, false,
                    "The data provider supplies these prices already " + sourceBasis.label()
                            + ". Aperture does not reverse that, because doing so safely needs "
                            + "every action the provider used. Showing the "
                            + sourceBasis.label().toLowerCase() + " series instead.");
        }

        List<Factor> factors = missingFactors(sorted, actions, sourceBasis, target);
        if (factors.isEmpty()) {
            return new AdjustedSeries(
                    sorted.stream().map(bar -> bar.withBasis(target)).toList(),
                    policy, target, true,
                    sourceBasis == target
                            ? "Already on the requested basis; no restatement needed."
                            : "No corporate actions fall inside this window.");
        }

        // Walk newest to oldest, folding in each factor once we pass behind its ex-date. Both
        // lists are in descending date order, so this is one linear pass.
        factors.sort(Comparator.comparing((Factor f) -> f.action.exDate()).reversed());

        Bar[] out = new Bar[sorted.size()];
        BigDecimal cumulativePrice = BigDecimal.ONE;
        BigDecimal cumulativeVolume = BigDecimal.ONE;
        int factorIndex = 0;

        for (int i = sorted.size() - 1; i >= 0; i--) {
            Bar bar = sorted.get(i);
            while (factorIndex < factors.size()
                    && factors.get(factorIndex).action.exDate().isAfter(bar.sessionDate())) {
                Factor factor = factors.get(factorIndex);
                cumulativePrice = cumulativePrice.multiply(factor.price);
                cumulativeVolume = cumulativeVolume.multiply(factor.volume);
                factorIndex++;
            }
            out[i] = bar.scaled(round(cumulativePrice), round(cumulativeVolume), target);
        }
        return new AdjustedSeries(List.of(out), policy, target, true,
                "Restated for " + factors.size() + " corporate action(s).");
    }

    /**
     * The cumulative price factor applied to a bar dated {@code asOf}. Exposed so a position's
     * cost basis can be restated on the same terms as the chart it is drawn against.
     */
    public static BigDecimal cumulativePriceFactor(List<Bar> bars, List<CorporateAction> actions,
                                                   AdjustmentPolicy policy, LocalDate asOf) {
        if (bars.isEmpty()) {
            return BigDecimal.ONE;
        }
        List<Bar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing(Bar::sessionDate));
        PriceBasis sourceBasis = sorted.get(0).basis();
        PriceBasis target = PriceBasis.forPolicy(policy);
        if (sourceBasis.wouldRequireUnadjusting(target)) {
            return BigDecimal.ONE;
        }
        BigDecimal cumulative = BigDecimal.ONE;
        for (Factor factor : missingFactors(sorted, actions, sourceBasis, target)) {
            if (factor.action.exDate().isAfter(asOf)) {
                cumulative = cumulative.multiply(factor.price);
            }
        }
        return round(cumulative);
    }

    /**
     * Factors for the actions that {@code target} requires and {@code source} has not already
     * applied.
     */
    private static List<Factor> missingFactors(List<Bar> ascending, List<CorporateAction> actions,
                                               PriceBasis source, PriceBasis target) {
        boolean needSplits = target.includesSplits() && !source.includesSplits();
        boolean needDividends = target.includesCashDividends() && !source.includesCashDividends();

        List<Factor> factors = new ArrayList<>();
        for (CorporateAction action : actions) {
            boolean wanted = switch (action) {
                case CorporateAction.Split ignored -> needSplits;
                case CorporateAction.StockDividend ignored -> needSplits;
                case CorporateAction.CashDividend ignored -> needDividends;
                case CorporateAction.SymbolChange ignored -> false;
            };
            if (!wanted) {
                continue;
            }
            Price previousClose = closeBefore(ascending, action.exDate());
            if (previousClose == null && action instanceof CorporateAction.CashDividend) {
                // No bar precedes the ex-date, so there is no close to take the dividend out of.
                // Skipping is the honest choice: inventing a denominator here produces a factor
                // that looks authoritative and is derived from nothing.
                continue;
            }
            BigDecimal price = action.priceAdjustmentFactor(
                    previousClose == null ? Price.zero() : previousClose);
            if (price.compareTo(BigDecimal.ONE) == 0) {
                continue;
            }
            factors.add(new Factor(action, price, action.quantityAdjustmentFactor()));
        }
        return factors;
    }

    /** The close of the last session strictly before {@code exDate}, or null if none exists. */
    private static Price closeBefore(List<Bar> ascending, LocalDate exDate) {
        Price found = null;
        for (Bar bar : ascending) {
            if (!bar.sessionDate().isBefore(exDate)) {
                break;
            }
            found = bar.close();
        }
        return found;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(CorporateAction.ADJUSTMENT_SCALE, RoundingMode.HALF_EVEN);
    }

    /** An action paired with the two factors derived from the incoming series. */
    private record Factor(CorporateAction action, BigDecimal price, BigDecimal volume) {
    }
}
