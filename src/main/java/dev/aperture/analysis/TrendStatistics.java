package dev.aperture.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a security's own history says about the odds of a target being reached in a given window.
 *
 * <p>This exists to make "should net a profit within a month" a falsifiable claim rather than a
 * feeling. The central number is {@link HitRate}: over every overlapping historical window of the
 * chosen length, how often did the price <em>touch</em> a given gain?
 *
 * <p>Touch, not close. A GTC sell limit fills the moment the market trades through it, so the
 * right statistic is the maximum high inside the window, not the closing price at the end of it.
 * Using closes would systematically understate how often a limit order gets filled.
 *
 * @param sessionsAnalysed how many daily bars the statistics were computed from
 * @param horizonSessions the forward window, in trading sessions
 * @param windows how many overlapping forward windows existed - the sample size behind every
 *     hit rate here
 * @param medianBestGainPercent the median of "the best gain available inside the window"
 * @param medianWorstDrawdownPercent the median of "the worst loss endured inside the window",
 *     which is what a holder actually has to sit through to reach the target
 */
public record TrendStatistics(
        String symbol,
        int sessionsAnalysed,
        int horizonSessions,
        int windows,
        BigDecimal lastClose,
        LocalDate lastSession,
        BigDecimal annualisedVolatilityPercent,
        BigDecimal medianBestGainPercent,
        BigDecimal medianWorstDrawdownPercent,
        List<HitRate> hitRates,
        Optional<BigDecimal> sma20,
        Optional<BigDecimal> sma50,
        Optional<BigDecimal> high52Week,
        Optional<BigDecimal> low52Week,
        BigDecimal trailingReturnPercent) {

    public TrendStatistics {
        Objects.requireNonNull(symbol, "symbol");
        hitRates = List.copyOf(hitRates);
    }

    /**
     * How often a gain of {@code targetPercent} was reachable inside the horizon.
     *
     * @param hitRatePercent share of windows in which the high touched the target
     * @param medianSessionsToHit typical time to the touch, among windows that hit - a target
     *     reached on day 19 of 21 is a very different proposition from one reached on day 3
     */
    public record HitRate(
            BigDecimal targetPercent,
            BigDecimal hitRatePercent,
            int windowsHit,
            Optional<Integer> medianSessionsToHit) {
    }

    /** The hit rate for the target closest to {@code targetPercent}. */
    public Optional<HitRate> hitRateFor(BigDecimal targetPercent) {
        return hitRates.stream()
                .min((a, b) -> a.targetPercent().subtract(targetPercent).abs()
                        .compareTo(b.targetPercent().subtract(targetPercent).abs()));
    }

    /** The largest target that was historically reached in at least {@code minHitRate}% of windows. */
    public Optional<HitRate> bestTargetAtConfidence(BigDecimal minHitRate) {
        return hitRates.stream()
                .filter(rate -> rate.hitRatePercent().compareTo(minHitRate) >= 0)
                .max((a, b) -> a.targetPercent().compareTo(b.targetPercent()));
    }

    /**
     * Whether the sample is large enough to quote odds from.
     *
     * <p>Overlapping windows are heavily autocorrelated, so the effective sample size is far
     * smaller than {@link #windows} suggests. Sixty is a low bar that still rules out the cases
     * where a hit rate would be arithmetic noise.
     */
    public boolean isSufficient() {
        return windows >= 60;
    }
}
