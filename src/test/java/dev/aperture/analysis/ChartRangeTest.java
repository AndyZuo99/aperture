package dev.aperture.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.marketdata.Bar;
import dev.aperture.time.MarketCalendar;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The windows behind the chart's range buttons.
 *
 * <p>The button is a promise about a span of time, and the vendor is asked for a count of bars.
 * Those are only the same thing for daily ranges, where one bar is one session - which is why the
 * intraday ranges are over-fetched and then cut to the window they claim.
 */
class ChartRangeTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @ParameterizedTest(name = "{0} parses to {1}")
    @CsvSource({
            "1W,  WEEK",
            "1M,  MONTH",
            "3M,  QUARTER",
            "1Y,  YEAR",
            "5Y,  FIVE_YEAR",
            "1w,  WEEK",
            "WEEK, WEEK",
            "'  1Y  ', YEAR",
    })
    void parsesLabelsAndNames(String input, ChartRange expected) {
        assertThat(ChartRange.parse(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a missing range is a year, not an error")
    void defaultsToAYear() {
        // The endpoint's range parameter is optional, and a chart with no window is not a chart.
        assertThat(ChartRange.parse(null)).isEqualTo(ChartRange.YEAR);
        assertThat(ChartRange.parse("")).isEqualTo(ChartRange.YEAR);
        assertThat(ChartRange.parse("   ")).isEqualTo(ChartRange.YEAR);
    }

    @Test
    @DisplayName("an unknown range is rejected rather than quietly becoming the default")
    void rejectsUnknownRange() {
        // Silently substituting a year would label the chart 10Y and draw one - the one thing the
        // basis switch exists to prevent, applied to the window instead of the adjustment.
        assertThatThrownBy(() -> ChartRange.parse("10Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10Y");
    }

    @ParameterizedTest
    @EnumSource(ChartRange.class)
    @DisplayName("no range asks for more bars than the vendor will serve")
    void staysWithinTheVendorCeiling(ChartRange range) {
        // "The count ranges is 1 to 1200" - exceeding it fails the request outright.
        assertThat(range.bars()).isBetween(1, ChartRange.MAX_BARS);
    }

    @Test
    @DisplayName("only the week is intraday, and only daily ranges can carry an adjustment")
    void granularityMatchesTheWindow() {
        // Five daily closes is a line between two prices, not a week's chart.
        assertThat(ChartRange.WEEK.isDaily()).isFalse();
        assertThat(ChartRange.WEEK.timespan()).isEqualTo("M30");

        for (ChartRange range : List.of(ChartRange.MONTH, ChartRange.QUARTER, ChartRange.YEAR,
                ChartRange.FIVE_YEAR)) {
            assertThat(range.isDaily()).isTrue();
            assertThat(range.timespan()).isEqualTo("D");
        }
    }

    @Test
    @DisplayName("the week trims an over-fetched intraday series to seven calendar days")
    void trimsIntradaySeriesToItsWindow() {
        // The real shape of the bug: 120 thirty-minute bars came back as eleven calendar days of
        // data under a button labelled 1W.
        List<Bar> bars = intradaySeries(LocalDate.of(2026, 9, 14), 11);

        List<Bar> trimmed = ChartRange.WEEK.trim(bars);

        assertThat(trimmed).isNotEmpty().hasSizeLessThan(bars.size());
        assertThat(trimmed.get(0).sessionDate())
                .isAfter(LocalDate.of(2026, 9, 14).minusDays(7));
        assertThat(trimmed.get(trimmed.size() - 1).sessionDate())
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("the window is measured from the last bar, not from today")
    void trimsRelativeToTheSeriesNotTheClock() {
        // A symbol that stopped trading, or a chart opened at the weekend, still shows its most
        // recent week rather than an empty plot.
        List<Bar> bars = intradaySeries(LocalDate.of(2026, 3, 20), 11);

        List<Bar> trimmed = ChartRange.WEEK.trim(bars);

        assertThat(trimmed.get(trimmed.size() - 1).sessionDate())
                .isEqualTo(LocalDate.of(2026, 3, 20));
        assertThat(trimmed.get(0).sessionDate()).isAfter(LocalDate.of(2026, 3, 13));
    }

    @Test
    @DisplayName("daily ranges are returned untouched, because the count is already the window")
    void dailyRangesAreNotTrimmed() {
        List<Bar> bars = intradaySeries(LocalDate.of(2026, 9, 14), 40);

        assertThat(ChartRange.YEAR.trim(bars)).isSameAs(bars);
        assertThat(ChartRange.FIVE_YEAR.trim(bars)).isSameAs(bars);
    }

    @Test
    @DisplayName("a series shorter than the window survives rather than being emptied")
    void shortSeriesAreKept() {
        // A newly listed symbol has less history than any button promises. Cutting it to nothing
        // would show an empty chart for a symbol that does have data.
        List<Bar> bars = intradaySeries(LocalDate.of(2026, 9, 14), 2);

        assertThat(ChartRange.WEEK.trim(bars)).hasSameSizeAs(bars);
        assertThat(ChartRange.WEEK.trim(List.of())).isEmpty();
    }

    /** {@code sessions} consecutive days of two half-hourly bars each, ending on {@code last}. */
    private static List<Bar> intradaySeries(LocalDate last, int sessions) {
        List<Bar> bars = new ArrayList<>();
        for (int day = sessions - 1; day >= 0; day--) {
            LocalDate date = last.minusDays(day);
            for (LocalTime time : List.of(LocalTime.of(9, 30), LocalTime.of(10, 0))) {
                bars.add(new Bar(AAPL, date,
                        date.atTime(time).atZone(MarketCalendar.EXCHANGE_ZONE).toInstant(),
                        Price.of(100), Price.of(101), Price.of(99), Price.of(100.5),
                        Quantity.of(1_000), PriceBasis.SPLIT_ADJUSTED));
            }
        }
        return bars;
    }
}
