package dev.aperture.corporate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The adjustment engine, which is where a silent order-of-magnitude error would live.
 *
 * <p>The series built here deliberately <em>contains</em> the discontinuity. Testing adjustment
 * against a smooth series proves nothing: adjustment would then be creating a jump rather than
 * removing one, and the test would pass either way.
 */
class PriceAdjusterTest {

    private static final InstrumentId NVDA = InstrumentId.of("NVDA");

    @Nested
    @DisplayName("splits on a raw series")
    class Splits {

        @Test
        @DisplayName("removes the discontinuity a split puts in a raw series")
        void removesSplitDiscontinuity() {
            // Two sessions across a 10-for-1: 1200 the day before, 120 on the ex-date. Raw, this
            // reads as a 90% collapse.
            List<Bar> raw = List.of(
                    rawBar("2024-06-07", 1200),
                    rawBar("2024-06-10", 121));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.satisfied()).isTrue();
            assertThat(series.basis()).isEqualTo(PriceBasis.SPLIT_ADJUSTED);
            // The earlier bar is restated into post-split terms...
            assertThat(series.bars().get(0).close().value())
                    .isCloseTo(BigDecimal.valueOf(120), within(BigDecimal.valueOf(0.001)));
            // ...and the ex-date bar is left exactly as it was.
            assertThat(series.bars().get(1).close().value())
                    .isCloseTo(BigDecimal.valueOf(121), within(BigDecimal.valueOf(0.001)));
        }

        @Test
        @DisplayName("a bar dated ON the ex-date is already post-split and is not touched")
        void exDateBarIsNotAdjusted() {
            // The off-by-one that leaves a one-session cliff in an otherwise correct chart.
            List<Bar> raw = List.of(
                    rawBar("2024-06-07", 1200),
                    rawBar("2024-06-10", 121),
                    rawBar("2024-06-11", 122));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.bars().get(1).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(121));
            assertThat(series.bars().get(2).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(122));
        }

        @Test
        @DisplayName("volume moves the opposite way to price")
        void volumeIsScaledInversely() {
            List<Bar> raw = List.of(
                    rawBar("2024-06-07", 1200, 1_000_000),
                    rawBar("2024-06-10", 121, 10_000_000));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            // Ten times as many shares at a tenth of the price: pre-split volume restated up 10x
            // so the two sessions are comparable.
            assertThat(series.bars().get(0).volume().value())
                    .isCloseTo(BigDecimal.valueOf(10_000_000), within(BigDecimal.valueOf(1)));
        }

        @Test
        @DisplayName("a reverse split scales the other way")
        void reverseSplit() {
            List<Bar> raw = List.of(
                    rawBar("2024-03-01", 2),
                    rawBar("2024-03-04", 16));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2024-03-04", 1, 8)), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.bars().get(0).close().value())
                    .isCloseTo(BigDecimal.valueOf(16), within(BigDecimal.valueOf(0.001)));
        }

        @Test
        @DisplayName("two splits compound rather than overwrite")
        void multipleSplitsCompound() {
            List<Bar> raw = List.of(
                    rawBar("2021-07-19", 4000),
                    rawBar("2021-07-20", 1000),
                    rawBar("2024-06-10", 120));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2021-07-20", 4, 1), split("2024-06-10", 10, 1)),
                    AdjustmentPolicy.SPLITS_ONLY);

            // 4000 sits behind both: /4 then /10 = /40.
            assertThat(series.bars().get(0).close().value())
                    .isCloseTo(BigDecimal.valueOf(100), within(BigDecimal.valueOf(0.001)));
            // 1000 sits behind only the 10-for-1.
            assertThat(series.bars().get(1).close().value())
                    .isCloseTo(BigDecimal.valueOf(100), within(BigDecimal.valueOf(0.001)));
        }
    }

    @Nested
    @DisplayName("guarding against double adjustment")
    class DoubleAdjustment {

        @Test
        @DisplayName("a source that is already split-adjusted is NOT adjusted again")
        void doesNotReapplySplitsToAdjustedSource() {
            // The trap this whole design exists for. Webull's bars arrive split-adjusted; an
            // engine that applied the split unconditionally would divide by ten a second time and
            // the resulting chart would look perfectly smooth at a tenth of the real price.
            List<Bar> vendorBars = List.of(
                    bar("2024-06-07", 120, PriceBasis.SPLIT_ADJUSTED),
                    bar("2024-06-10", 121, PriceBasis.SPLIT_ADJUSTED));

            AdjustedSeries series = PriceAdjuster.adjust(vendorBars,
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.satisfied()).isTrue();
            assertThat(series.bars().get(0).close().toDisplay())
                    .describedAs("must stay 120, not become 12")
                    .isEqualByComparingTo(BigDecimal.valueOf(120));
        }

        @Test
        @DisplayName("on a split-adjusted source, a total-return request applies dividends only")
        void appliesOnlyTheMissingDividendAdjustment() {
            List<Bar> vendorBars = List.of(
                    bar("2026-03-10", 100, PriceBasis.SPLIT_ADJUSTED),
                    bar("2026-03-11", 100, PriceBasis.SPLIT_ADJUSTED));

            AdjustedSeries series = PriceAdjuster.adjust(vendorBars,
                    List.of(split("2026-03-11", 10, 1), dividend("2026-03-11", "1.00")),
                    AdjustmentPolicy.TOTAL_RETURN);

            // Only the dividend is missing from the source basis: (100 - 1) / 100 = 0.99.
            // If the split were also applied the answer would be 9.9.
            assertThat(series.bars().get(0).close().value())
                    .isCloseTo(BigDecimal.valueOf(99), within(BigDecimal.valueOf(0.001)));
        }

        @Test
        @DisplayName("refuses to un-adjust rather than inventing raw prices")
        void refusesToUnadjust() {
            List<Bar> vendorBars = List.of(
                    bar("2024-06-07", 120, PriceBasis.SPLIT_ADJUSTED),
                    bar("2024-06-10", 121, PriceBasis.SPLIT_ADJUSTED));

            AdjustedSeries series = PriceAdjuster.adjust(vendorBars,
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.NONE);

            // Reversing a provider's adjustment needs every action they used. Aperture's table is
            // curated, so it returns what it has and says the request was not satisfied.
            assertThat(series.satisfied()).isFalse();
            assertThat(series.basis()).isEqualTo(PriceBasis.SPLIT_ADJUSTED);
            assertThat(series.note()).contains("already");
            assertThat(series.bars().get(0).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(120));
        }
    }

    @Nested
    @DisplayName("cash dividends")
    class Dividends {

        @Test
        @DisplayName("adjusts proportionally to the close the dividend came out of")
        void proportionalToPreviousClose() {
            List<Bar> raw = List.of(
                    rawBar("2026-03-10", 100),
                    rawBar("2026-03-11", 99));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(dividend("2026-03-11", "1.00")), AdjustmentPolicy.TOTAL_RETURN);

            // (100 - 1) / 100 = 0.99, so 100 -> 99. The price return is then flat, which is
            // correct: a holder was made whole in cash.
            assertThat(series.bars().get(0).close().value())
                    .isCloseTo(BigDecimal.valueOf(99), within(BigDecimal.valueOf(0.001)));
        }

        @Test
        @DisplayName("the same dividend is a bigger adjustment on a cheaper stock")
        void adjustmentIsProportionalNotAbsolute() {
            AdjustedSeries cheap = PriceAdjuster.adjust(
                    List.of(rawBar("2026-03-10", 20), rawBar("2026-03-11", 19)),
                    List.of(dividend("2026-03-11", "1.00")), AdjustmentPolicy.TOTAL_RETURN);
            AdjustedSeries dear = PriceAdjuster.adjust(
                    List.of(rawBar("2026-03-10", 200), rawBar("2026-03-11", 199)),
                    List.of(dividend("2026-03-11", "1.00")), AdjustmentPolicy.TOTAL_RETURN);

            BigDecimal cheapDrop = BigDecimal.valueOf(20)
                    .subtract(cheap.bars().get(0).close().value());
            BigDecimal dearDrop = BigDecimal.valueOf(200)
                    .subtract(dear.bars().get(0).close().value());
            assertThat(cheapDrop).isEqualByComparingTo(dearDrop);
        }

        @Test
        @DisplayName("dividends are ignored under a splits-only policy")
        void splitsOnlyIgnoresDividends() {
            List<Bar> raw = List.of(rawBar("2026-03-10", 100), rawBar("2026-03-11", 99));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(dividend("2026-03-11", "1.00")), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.bars().get(0).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(100));
        }

        @Test
        @DisplayName("a dividend with no preceding bar is skipped, not guessed at")
        void dividendBeforeSeriesStartIsSkipped() {
            // There is no close to take the dividend out of, so there is no defensible factor.
            // Inventing a denominator would produce an authoritative-looking wrong number.
            List<Bar> raw = List.of(rawBar("2026-03-11", 99), rawBar("2026-03-12", 100));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(dividend("2026-03-11", "1.00")), AdjustmentPolicy.TOTAL_RETURN);

            assertThat(series.bars().get(0).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(99));
        }

        @Test
        @DisplayName("a dividend at or above the previous close does not zero out history")
        void absurdDividendIsIgnored() {
            List<Bar> raw = List.of(rawBar("2026-03-10", 1), rawBar("2026-03-11", 1));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(dividend("2026-03-11", "5.00")), AdjustmentPolicy.TOTAL_RETURN);

            // A factor of zero or less would flatten every earlier price to nothing - worse than
            // not adjusting at all.
            assertThat(series.bars().get(0).close().value()).isPositive();
        }
    }

    @Nested
    @DisplayName("general behaviour")
    class General {

        @Test
        @DisplayName("the input list is never mutated")
        void doesNotMutateInput() {
            List<Bar> raw = new ArrayList<>(List.of(
                    rawBar("2024-06-10", 121), rawBar("2024-06-07", 1200)));
            List<Bar> before = List.copyOf(raw);

            PriceAdjuster.adjust(raw, List.of(split("2024-06-10", 10, 1)),
                    AdjustmentPolicy.SPLITS_ONLY);

            assertThat(raw).isEqualTo(before);
        }

        @Test
        @DisplayName("output is sorted ascending regardless of input order")
        void sortsAscending() {
            List<Bar> unsorted = List.of(
                    rawBar("2024-06-11", 122), rawBar("2024-06-07", 1200),
                    rawBar("2024-06-10", 121));

            AdjustedSeries series = PriceAdjuster.adjust(unsorted, List.of(),
                    AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.bars()).map(Bar::sessionDate)
                    .containsExactly(LocalDate.parse("2024-06-07"),
                            LocalDate.parse("2024-06-10"), LocalDate.parse("2024-06-11"));
        }

        @Test
        @DisplayName("an action outside the window changes nothing")
        void actionOutsideWindowIsInert() {
            List<Bar> raw = List.of(rawBar("2026-01-05", 100), rawBar("2026-01-06", 101));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(split("2019-01-01", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            // The ex-date is before every bar, so nothing sits behind it.
            assertThat(series.bars().get(0).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(100));
        }

        @Test
        @DisplayName("an empty series is handled without adjustment")
        void emptySeries() {
            AdjustedSeries series = PriceAdjuster.adjust(List.of(),
                    List.of(split("2024-06-10", 10, 1)), AdjustmentPolicy.SPLITS_ONLY);

            assertThat(series.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a symbol change moves neither prices nor share counts")
        void symbolChangeIsInert() {
            List<Bar> raw = List.of(rawBar("2022-06-08", 100), rawBar("2022-06-09", 101));

            AdjustedSeries series = PriceAdjuster.adjust(raw,
                    List.of(new CorporateAction.SymbolChange(
                            NVDA, LocalDate.parse("2022-06-09"), "FB", "META")),
                    AdjustmentPolicy.TOTAL_RETURN);

            assertThat(series.bars().get(0).close().toDisplay())
                    .isEqualByComparingTo(BigDecimal.valueOf(100));
        }
    }

    // --- helpers ---

    private static Bar rawBar(String date, double close) {
        return rawBar(date, close, 1_000_000);
    }

    private static Bar rawBar(String date, double close, long volume) {
        return new Bar(NVDA, LocalDate.parse(date),
                Price.of(close), Price.of(close), Price.of(close), Price.of(close),
                Quantity.of(volume), PriceBasis.RAW);
    }

    private static Bar bar(String date, double close, PriceBasis basis) {
        return new Bar(NVDA, LocalDate.parse(date),
                Price.of(close), Price.of(close), Price.of(close), Price.of(close),
                Quantity.of(1_000_000L), basis);
    }

    private static CorporateAction split(String exDate, long newShares, long oldShares) {
        return CorporateAction.Split.forward(NVDA, LocalDate.parse(exDate), newShares, oldShares);
    }

    private static CorporateAction dividend(String exDate, String amount) {
        LocalDate ex = LocalDate.parse(exDate);
        return new CorporateAction.CashDividend(NVDA, ex, ex.plusDays(14),
                Money.usd(new BigDecimal(amount)));
    }
}
