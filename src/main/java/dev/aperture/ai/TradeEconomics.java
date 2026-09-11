package dev.aperture.ai;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The arithmetic behind a recommendation, computed by Aperture from live prices and the
 * instrument's own history.
 *
 * <p>Deliberately <strong>not</strong> taken from the model. A language model asked for an
 * expected profit will produce a confident number, and that number is unverifiable and often
 * wrong. Here the model chooses only the symbol, the size and the target; every figure below is
 * derived - so a recommendation cannot claim a profit the prices do not support, and the
 * historical hit rate is recomputed from the bars rather than quoted back.
 *
 * @param entryPrice the ask, because a market buy crosses the spread and pays it
 * @param spreadCostPercent what crossing costs immediately, as a share of the mid
 * @param historicalHitRatePercent how often this exact target was touched within the horizon
 *     historically; empty when there is not enough history to say
 * @param medianDrawdownPercent the typical worst loss endured inside the window before the target
 *     was reached - the part of the plan that is easy to leave out
 * @param rewardToRisk expected gain against that typical drawdown
 * @param warnings reasons this plan is questionable, from checks the model does not run on itself
 */
public record TradeEconomics(
        Price entryPrice,
        Price targetPrice,
        Money notional,
        Money grossProfit,
        BigDecimal returnPercent,
        BigDecimal spreadCostPercent,
        BigDecimal netReturnAfterSpreadPercent,
        Optional<BigDecimal> historicalHitRatePercent,
        Optional<Integer> historicalWindows,
        Optional<Integer> medianSessionsToHit,
        BigDecimal medianDrawdownPercent,
        Optional<BigDecimal> rewardToRisk,
        List<String> warnings,
        boolean viable) {

    public TradeEconomics {
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(targetPrice, "targetPrice");
        warnings = List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
