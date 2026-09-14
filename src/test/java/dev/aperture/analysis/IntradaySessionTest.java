package dev.aperture.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.corporate.PriceBasis;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.marketdata.Bar;
import dev.aperture.time.MarketCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One trading day, summarised from the vendor's rolling intraday window.
 *
 * <p>The window is the trap: {@code getBatchBars} with {@code M1} returns several days of minute
 * bars, so a summary that takes them at face value reports a two-day range and yesterday's open
 * as today's. Every figure here is checked against a series that deliberately spans two sessions.
 */
class IntradaySessionTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 11);

    /**
     * Today: two bars whose volume-weighted average works out to exactly 106.
     *
     * <p>Typical prices are 100 on 1,000 shares and 108 on 3,000, so the VWAP is
     * 424,000 / 4,000 - a figure that can be checked by hand rather than by re-running the
     * implementation.
     */
    private static List<Bar> twoSessions() {
        return List.of(
                // Yesterday, at prices nothing like today's so any leakage is unmistakable.
                bar(YESTERDAY, LocalTime.of(9, 30), 50, 60, 40, 55, 9_000),
                bar(YESTERDAY, LocalTime.of(15, 59), 55, 58, 52, 96, 9_000),
                bar(TODAY, LocalTime.of(9, 30), 99, 102, 98, 100, 1_000),
                bar(TODAY, LocalTime.of(9, 31), 106, 110, 106, 108, 3_000));
    }

    @Test
    @DisplayName("only the latest session is summarised, so the open is today's open")
    void trimsToTheLatestSession() {
        IntradaySession session = sessionOf(twoSessions(), BigDecimal.valueOf(96));

        assertThat(session.sessionDate()).isEqualTo(TODAY);
        assertThat(session.bars()).hasSize(2);
        // Yesterday's 50 was the earliest bar in the window but is not today's open.
        assertThat(session.open()).isEqualByComparingTo("99.00");
        // Nor is yesterday's 40 today's low.
        assertThat(session.low()).isEqualByComparingTo("98.00");
        assertThat(session.high()).isEqualByComparingTo("110.00");
        assertThat(session.last()).isEqualByComparingTo("108.00");
        assertThat(session.volume()).isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("VWAP weights the typical price by volume, not the close by bar count")
    void vwapUsesTypicalPriceAndVolume() {
        // A bar that opened low and closed high did not trade all of its volume at the close, and
        // the second bar here carries three times the first one's - a plain average of closes
        // would give 104 rather than 106.
        IntradaySession session = sessionOf(twoSessions(), BigDecimal.valueOf(96));

        assertThat(session.vwap()).hasValueSatisfying(
                vwap -> assertThat(vwap).isEqualByComparingTo("106.00"));
        assertThat(session.isAboveVwap()).contains(true);
    }

    @Test
    @DisplayName("a session with no volume has no VWAP rather than a divide-by-zero")
    void noVolumeMeansNoVwap() {
        IntradaySession session = sessionOf(List.of(
                bar(TODAY, LocalTime.of(9, 30), 99, 102, 98, 100, 0)), BigDecimal.valueOf(96));

        assertThat(session.vwap()).isEmpty();
        assertThat(session.isAboveVwap()).isEmpty();
    }

    @Test
    @DisplayName("change is reported against both today's open and the prior close")
    void changesAgainstOpenAndPriorClose() {
        IntradaySession session = sessionOf(twoSessions(), BigDecimal.valueOf(96));

        assertThat(session.changeFromOpen()).isEqualByComparingTo("9.00");
        assertThat(session.changeFromOpenPercent()).isEqualByComparingTo("9.09");
        assertThat(session.changeFromPreviousClose())
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("12.00"));
        assertThat(session.changeFromPreviousClosePercent())
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("12.50"));
    }

    @Test
    @DisplayName("an unknown prior close leaves the comparison empty rather than inventing one")
    void missingPriorCloseIsNotZero() {
        // Treating an absent close as zero would print a change of +108 and a percentage that is
        // either infinite or a lie; the quote line simply has nothing to show.
        for (BigDecimal prior : new BigDecimal[] {null, BigDecimal.ZERO, BigDecimal.valueOf(-5)}) {
            IntradaySession session = sessionOf(twoSessions(), prior);

            assertThat(session.previousClose()).isEmpty();
            assertThat(session.changeFromPreviousClose()).isEmpty();
            assertThat(session.changeFromPreviousClosePercent()).isEmpty();
            // The open-relative figures still work, so the panel is not blank.
            assertThat(session.changeFromOpen()).isEqualByComparingTo("9.00");
        }
    }

    @Test
    @DisplayName("range position says where the last trade sits between the day's low and high")
    void rangePosition() {
        // 108 in a 98-110 range: pressing the highs, which a +9% change alone does not say.
        assertThat(sessionOf(twoSessions(), null).rangePosition())
                .isEqualByComparingTo("83.3");
    }

    @Test
    @DisplayName("a flat session sits in the middle rather than dividing by zero")
    void flatRangeIsMidpoint() {
        // One bar that never moved - real for an illiquid event contract in the first minute.
        IntradaySession session = sessionOf(List.of(
                bar(TODAY, LocalTime.of(9, 30), 100, 100, 100, 100, 500)), null);

        assertThat(session.rangePosition()).isEqualByComparingTo("50");
        assertThat(session.changeFromOpenPercent()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("bars are ordered by time, whatever order the vendor returned them in")
    void ordersBarsByTime() {
        // Open and last are the ends of the session, not the ends of the array.
        List<Bar> shuffled = List.of(
                bar(TODAY, LocalTime.of(9, 31), 106, 110, 106, 108, 3_000),
                bar(TODAY, LocalTime.of(9, 30), 99, 102, 98, 100, 1_000));

        IntradaySession session = sessionOf(shuffled, null);

        assertThat(session.open()).isEqualByComparingTo("99.00");
        assertThat(session.last()).isEqualByComparingTo("108.00");
        assertThat(session.bars().get(0).startTime())
                .isBefore(session.bars().get(1).startTime());
    }

    @Test
    @DisplayName("no bars is no session, not an empty one")
    void noBarsMeansNoSession() {
        // A futures contract or a symbol before its first trade: the view must be able to say
        // there is nothing, rather than render zeroes as prices.
        assertThat(IntradaySession.from("AAPL", List.of(), null)).isEmpty();
        assertThat(IntradaySession.from("AAPL", null, null)).isEmpty();
    }

    @Test
    @DisplayName("the bar list is defensively copied")
    void barsAreImmutable() {
        IntradaySession session = sessionOf(twoSessions(), null);

        assertThat(session.bars()).isUnmodifiable();
    }

    private static IntradaySession sessionOf(List<Bar> bars, BigDecimal previousClose) {
        Optional<IntradaySession> session = IntradaySession.from("AAPL", bars, previousClose);
        assertThat(session).isPresent();
        return session.get();
    }

    private static Bar bar(LocalDate date, LocalTime time,
                           double open, double high, double low, double close, long volume) {
        return new Bar(AAPL, date,
                date.atTime(time).atZone(MarketCalendar.EXCHANGE_ZONE).toInstant(),
                Price.of(open), Price.of(high), Price.of(low), Price.of(close),
                Quantity.of(volume), PriceBasis.SPLIT_ADJUSTED);
    }
}
