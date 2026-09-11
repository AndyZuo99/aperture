package dev.aperture.analysis;

import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Computes {@link TrendStatistics} from a daily bar series.
 *
 * <h2>The question this answers</h2>
 *
 * <p>"If I buy today and rest a sell limit {@code T}% above, how often has that limit historically
 * filled within {@code N} sessions - and what would I have had to sit through first?"
 *
 * <p>For every historical bar it looks forward {@code N} sessions and records the best gain
 * available (the maximum high) and the worst drawdown endured (the minimum low). The distribution
 * of those two numbers is the entire basis for a target being callable "achievable" or not.
 *
 * <h2>Caveats that are built in rather than assumed away</h2>
 *
 * <ul>
 *   <li><strong>Adjusted bars only.</strong> A split inside the window would otherwise register as
 *       a 90% drawdown that never happened.
 *   <li><strong>Overlapping windows are autocorrelated.</strong> 200 windows over 250 sessions are
 *       nothing like 200 independent trials, so {@link TrendStatistics#isSufficient()} gates on
 *       sample size and the UI states the window count next to every rate.
 *   <li><strong>This is descriptive, not predictive.</strong> It reports what the past did. It is
 *       the honest input to a judgement, not a forecast, and nothing here should be presented as
 *       one.
 * </ul>
 */
public final class TrendAnalyzer {

    /** Targets to evaluate, in percent. Spaced to cover realistic one-month equity moves. */
    private static final List<BigDecimal> DEFAULT_TARGETS = List.of(
            BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
            BigDecimal.valueOf(5), BigDecimal.valueOf(7), BigDecimal.valueOf(10),
            BigDecimal.valueOf(15));

    /** Roughly one calendar month of trading. */
    public static final int ONE_MONTH_SESSIONS = 21;

    private static final int SCALE = 4;

    private TrendAnalyzer() {
    }

    public static Optional<TrendStatistics> analyse(String symbol, List<Bar> bars) {
        return analyse(symbol, bars, ONE_MONTH_SESSIONS, DEFAULT_TARGETS);
    }

    /**
     * @param bars ascending daily bars, already adjusted for corporate actions
     * @param horizonSessions forward window length
     */
    public static Optional<TrendStatistics> analyse(String symbol, List<Bar> bars,
                                                    int horizonSessions,
                                                    List<BigDecimal> targets) {
        if (bars == null || bars.size() < horizonSessions + 2 || horizonSessions < 1) {
            return Optional.empty();
        }

        // Every entry point that has a full forward window after it. A partial window would
        // understate the hit rate simply by having had less time to hit.
        int windowCount = bars.size() - horizonSessions;

        List<BigDecimal> bestGains = new ArrayList<>(windowCount);
        List<BigDecimal> worstDrawdowns = new ArrayList<>(windowCount);
        // For each target, the sessions-to-hit of every window that hit it.
        List<List<Integer>> sessionsToHit = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            sessionsToHit.add(new ArrayList<>());
        }

        for (int entry = 0; entry < windowCount; entry++) {
            BigDecimal entryPrice = bars.get(entry).close().value();
            if (entryPrice.signum() <= 0) {
                continue;
            }
            BigDecimal bestHigh = null;
            BigDecimal worstLow = null;
            // Tracks the first session each target was touched, so a target hit early is not
            // recorded as though it took the whole window.
            int[] firstHit = new int[targets.size()];
            java.util.Arrays.fill(firstHit, -1);

            for (int offset = 1; offset <= horizonSessions; offset++) {
                Bar forward = bars.get(entry + offset);
                BigDecimal high = forward.high().value();
                BigDecimal low = forward.low().value();
                if (bestHigh == null || high.compareTo(bestHigh) > 0) {
                    bestHigh = high;
                }
                if (worstLow == null || low.compareTo(worstLow) < 0) {
                    worstLow = low;
                }
                for (int t = 0; t < targets.size(); t++) {
                    if (firstHit[t] >= 0) {
                        continue;
                    }
                    BigDecimal targetPrice = entryPrice.multiply(
                            BigDecimal.ONE.add(targets.get(t).movePointLeft(2)));
                    if (high.compareTo(targetPrice) >= 0) {
                        firstHit[t] = offset;
                    }
                }
            }
            if (bestHigh == null || worstLow == null) {
                continue;
            }
            bestGains.add(percentChange(entryPrice, bestHigh));
            worstDrawdowns.add(percentChange(entryPrice, worstLow));
            for (int t = 0; t < targets.size(); t++) {
                if (firstHit[t] >= 0) {
                    sessionsToHit.get(t).add(firstHit[t]);
                }
            }
        }

        if (bestGains.isEmpty()) {
            return Optional.empty();
        }

        List<TrendStatistics.HitRate> hitRates = new ArrayList<>(targets.size());
        for (int t = 0; t < targets.size(); t++) {
            List<Integer> hits = sessionsToHit.get(t);
            BigDecimal rate = BigDecimal.valueOf(hits.size())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(bestGains.size()), 2, RoundingMode.HALF_EVEN);
            hitRates.add(new TrendStatistics.HitRate(
                    targets.get(t), rate, hits.size(), medianInt(hits)));
        }

        Bar last = bars.get(bars.size() - 1);
        return Optional.of(new TrendStatistics(
                symbol,
                bars.size(),
                horizonSessions,
                bestGains.size(),
                last.close().value().setScale(2, RoundingMode.HALF_EVEN),
                last.sessionDate(),
                annualisedVolatility(bars),
                median(bestGains),
                median(worstDrawdowns),
                hitRates,
                simpleMovingAverage(bars, 20),
                simpleMovingAverage(bars, 50),
                extreme(bars, true),
                extreme(bars, false),
                trailingReturn(bars)));
    }

    private static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        return to.subtract(from)
                .multiply(BigDecimal.valueOf(100))
                .divide(from, SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_EVEN);
    }

    private static Optional<Integer> medianInt(List<Integer> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return Optional.of(sorted.get(sorted.size() / 2));
    }

    private static Optional<BigDecimal> simpleMovingAverage(List<Bar> bars, int period) {
        if (bars.size() < period) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            total = total.add(bars.get(i).close().value());
        }
        return Optional.of(total.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_EVEN));
    }

    /** The highest high or lowest low over the trailing year, as far as the series reaches. */
    private static Optional<BigDecimal> extreme(List<Bar> bars, boolean high) {
        int from = Math.max(0, bars.size() - 252);
        BigDecimal found = null;
        for (int i = from; i < bars.size(); i++) {
            BigDecimal value = high ? bars.get(i).high().value() : bars.get(i).low().value();
            if (found == null || (high ? value.compareTo(found) > 0 : value.compareTo(found) < 0)) {
                found = value;
            }
        }
        return Optional.ofNullable(found).map(v -> v.setScale(2, RoundingMode.HALF_EVEN));
    }

    private static BigDecimal trailingReturn(List<Bar> bars) {
        BigDecimal first = bars.get(0).close().value();
        BigDecimal last = bars.get(bars.size() - 1).close().value();
        return first.signum() <= 0 ? BigDecimal.ZERO : percentChange(first, last);
    }

    private static BigDecimal annualisedVolatility(List<Bar> bars) {
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        for (int i = 1; i < bars.size(); i++) {
            double previous = bars.get(i - 1).close().value().doubleValue();
            double current = bars.get(i).close().value().doubleValue();
            if (previous <= 0 || current <= 0) {
                continue;
            }
            double logReturn = Math.log(current / previous);
            sum += logReturn;
            sumSquares += logReturn * logReturn;
            count++;
        }
        if (count < 2) {
            return BigDecimal.ZERO;
        }
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        if (variance <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.sqrt(variance) * Math.sqrt(252) * 100)
                .setScale(2, RoundingMode.HALF_EVEN);
    }
}
