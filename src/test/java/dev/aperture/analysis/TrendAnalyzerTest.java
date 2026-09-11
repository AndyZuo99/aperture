package dev.aperture.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statistics that make "should profit within a month" checkable.
 *
 * <p>The series here are constructed so the right answer is known by hand - the point is to pin
 * down what "hit rate" actually counts, because getting that subtly wrong produces numbers that
 * look plausible and are not.
 */
class TrendAnalyzerTest {

    private static final InstrumentId ID = InstrumentId.of("TEST");

    @Test
    @DisplayName("a hit counts an intraday TOUCH of the target, not a close above it")
    void hitRateCountsTouchesNotCloses() {
        // Every bar closes flat at 100 but each high reaches 105. A resting sell limit at +3%
        // fills on the touch, so the honest hit rate is 100% - measuring closes would say 0%.
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            bars.add(bar(i, 100, 105, 99, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 5,
                List.of(BigDecimal.valueOf(3))).orElseThrow();

        assertThat(stats.hitRates().get(0).hitRatePercent())
                .isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("a target beyond every high is never hit")
    void unreachableTargetIsNeverHit() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            bars.add(bar(i, 100, 101, 99, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 5,
                List.of(BigDecimal.valueOf(10))).orElseThrow();

        assertThat(stats.hitRates().get(0).hitRatePercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.hitRates().get(0).windowsHit()).isZero();
    }

    @Test
    @DisplayName("the horizon is respected - a move after the window does not count")
    void moveOutsideTheHorizonDoesNotCount() {
        // Flat throughout except one spike at index 30, with flat bars after it so that entries
        // on both sides of the horizon boundary exist.
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            bars.add(bar(i, 100, 100.5, 99.5, 100));
        }
        bars.add(bar(30, 100, 130, 99, 100));
        for (int i = 31; i < 34; i++) {
            bars.add(bar(i, 100, 100.5, 99.5, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 3,
                List.of(BigDecimal.valueOf(10))).orElseThrow();

        // Entry i looks at i+1..i+3, so only entries 27, 28 and 29 contain the spike at 30.
        // Entry 30 is too late and entry 26 too early - the horizon cuts both ways.
        assertThat(stats.hitRates().get(0).windowsHit()).isEqualTo(3);
        // 34 bars minus a 3-session horizon: entries 0..30 each have a complete window. An entry
        // with only a partial window is excluded rather than counted as a miss, which would
        // understate every hit rate by punishing recency.
        assertThat(stats.windows()).isEqualTo(31);
    }

    @Test
    @DisplayName("time-to-hit is measured to the first touch, not the end of the window")
    void medianSessionsToHitUsesFirstTouch() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // The target is available on the very next session every time.
            bars.add(bar(i, 100, 110, 99, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 10,
                List.of(BigDecimal.valueOf(5))).orElseThrow();

        assertThat(stats.hitRates().get(0).medianSessionsToHit()).contains(1);
    }

    @Test
    @DisplayName("drawdown is measured from the lows inside the window")
    void drawdownUsesLows() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            bars.add(bar(i, 100, 101, 90, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 5,
                List.of(BigDecimal.valueOf(3))).orElseThrow();

        // Lows are 10% below every entry, so the median worst drawdown is -10%.
        assertThat(stats.medianWorstDrawdownPercent())
                .isCloseTo(BigDecimal.valueOf(-10), org.assertj.core.data.Offset.offset(
                        BigDecimal.valueOf(0.01)));
    }

    @Test
    @DisplayName("a series shorter than the horizon yields nothing rather than a bogus rate")
    void tooShortASeriesProducesNoStatistics() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, 100, 101, 99, 100));
        }

        assertThat(TrendAnalyzer.analyse("TEST", bars, 21, List.of(BigDecimal.valueOf(3))))
                .isEmpty();
    }

    @Test
    @DisplayName("a small sample is reported as insufficient rather than quoted with confidence")
    void smallSamplesAreFlagged() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            bars.add(bar(i, 100, 105, 99, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 21,
                List.of(BigDecimal.valueOf(3))).orElseThrow();

        // Overlapping windows are heavily correlated, so a handful of them is not evidence.
        assertThat(stats.windows()).isLessThan(60);
        assertThat(stats.isSufficient()).isFalse();
    }

    @Test
    @DisplayName("higher targets are hit no more often than lower ones")
    void hitRatesAreMonotonic() {
        // A basic sanity property: if +5% was reached, +3% necessarily was too.
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            double high = 100 + (i % 11);
            bars.add(bar(i, 100, high, 97, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 10,
                List.of(BigDecimal.valueOf(1), BigDecimal.valueOf(3), BigDecimal.valueOf(5),
                        BigDecimal.valueOf(8))).orElseThrow();

        for (int i = 1; i < stats.hitRates().size(); i++) {
            assertThat(stats.hitRates().get(i).hitRatePercent())
                    .isLessThanOrEqualTo(stats.hitRates().get(i - 1).hitRatePercent());
        }
    }

    @Test
    @DisplayName("the largest target meeting a confidence floor is selectable")
    void bestTargetAtConfidence() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            bars.add(bar(i, 100, 104, 99, 100));
        }

        TrendStatistics stats = TrendAnalyzer.analyse("TEST", bars, 5,
                List.of(BigDecimal.valueOf(1), BigDecimal.valueOf(3), BigDecimal.valueOf(8)))
                .orElseThrow();

        Optional<TrendStatistics.HitRate> best =
                stats.bestTargetAtConfidence(BigDecimal.valueOf(90));

        // +3% is always reachable (high 104), +8% never is.
        assertThat(best).isPresent();
        assertThat(best.get().targetPercent()).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    private static Bar bar(int dayOffset, double open, double high, double low, double close) {
        return new Bar(ID, LocalDate.parse("2026-01-05").plusDays(dayOffset),
                Price.of(open), Price.of(high), Price.of(low), Price.of(close),
                Quantity.of(1_000_000L), PriceBasis.SPLIT_ADJUSTED);
    }
}
