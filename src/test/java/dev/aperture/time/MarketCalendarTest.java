package dev.aperture.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The exchange calendar.
 *
 * <p>Holidays are derived from rules rather than a table, so these assertions are checking the
 * rules against known-good dates across several years - including the ones with awkward
 * weekend-observance behaviour.
 */
class MarketCalendarTest {

    @ParameterizedTest(name = "{0} is a holiday")
    @CsvSource({
            "2026-01-01",  // New Year's Day
            "2026-01-19",  // MLK Day, 3rd Monday
            "2026-02-16",  // Presidents' Day, 3rd Monday
            "2026-04-03",  // Good Friday
            "2026-05-25",  // Memorial Day, last Monday
            "2026-06-19",  // Juneteenth
            "2026-07-03",  // Independence Day observed (the 4th is a Saturday)
            "2026-09-07",  // Labor Day
            "2026-11-26",  // Thanksgiving
            "2026-12-25",  // Christmas
    })
    void recognisesHolidays(String date) {
        assertThat(MarketCalendar.isHoliday(LocalDate.parse(date))).isTrue();
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse(date))).isFalse();
    }

    @Test
    @DisplayName("Good Friday moves with Easter")
    void goodFridayAcrossYears() {
        // The one holiday with neither a fixed date nor an nth-weekday rule.
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2024-03-29"))).isTrue();
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2025-04-18"))).isTrue();
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2026-04-03"))).isTrue();
    }

    @Test
    @DisplayName("a fixed holiday on a Saturday is observed the Friday before")
    void saturdayHolidayObservedFriday() {
        // 4 July 2026 is a Saturday, so the market closes Friday the 3rd.
        assertThat(LocalDate.parse("2026-07-04").getDayOfWeek().getValue()).isEqualTo(6);
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2026-07-03"))).isTrue();
    }

    @Test
    @DisplayName("a fixed holiday on a Sunday is observed the Monday after")
    void sundayHolidayObservedMonday() {
        // 25 December 2022 was a Sunday; the market closed Monday the 26th.
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2022-12-26"))).isTrue();
    }

    @Test
    @DisplayName("Juneteenth is not a holiday before 2022")
    void juneteenthOnlyFrom2022() {
        // It became a market holiday in 2022; asserting the absence stops the rule being applied
        // retroactively to history that was actually traded.
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2021-06-18"))).isFalse();
        assertThat(MarketCalendar.isHoliday(LocalDate.parse("2022-06-20"))).isTrue();
    }

    @Test
    @DisplayName("weekends are not trading days")
    void weekends() {
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse("2026-09-11"))).isTrue();   // Fri
        assertThat(MarketCalendar.isWeekend(LocalDate.parse("2026-09-12"))).isTrue();      // Sat
        assertThat(MarketCalendar.isWeekend(LocalDate.parse("2026-09-13"))).isTrue();      // Sun
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse("2026-09-14"))).isTrue();   // Mon
    }

    @Test
    @DisplayName("the day after Thanksgiving closes early")
    void blackFridayIsAHalfDay() {
        LocalDate blackFriday = LocalDate.parse("2026-11-27");
        assertThat(MarketCalendar.isEarlyClose(blackFriday)).isTrue();
        assertThat(MarketCalendar.regularCloseOn(blackFriday)).isEqualTo(LocalTime.of(13, 0));
        assertThat(MarketCalendar.postMarketCloseOn(blackFriday)).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("an ordinary session closes at 16:00")
    void ordinarySessionClose() {
        LocalDate ordinary = LocalDate.parse("2026-09-11");
        assertThat(MarketCalendar.isEarlyClose(ordinary)).isFalse();
        assertThat(MarketCalendar.regularCloseOn(ordinary)).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    @DisplayName("a holiday is never also an early close")
    void holidayIsNotAnEarlyClose() {
        assertThat(MarketCalendar.isEarlyClose(LocalDate.parse("2026-12-25"))).isFalse();
    }

    @Test
    @DisplayName("next and previous trading day step over weekends and holidays")
    void navigation() {
        // Friday 2026-09-11 -> Monday 2026-09-14.
        assertThat(MarketCalendar.nextTradingDay(LocalDate.parse("2026-09-11")))
                .isEqualTo(LocalDate.parse("2026-09-14"));
        // The day before Christmas Day 2026 (a Friday) is Thursday the 24th.
        assertThat(MarketCalendar.previousTradingDay(LocalDate.parse("2026-12-28")))
                .isEqualTo(LocalDate.parse("2026-12-24"));
    }

    @Test
    @DisplayName("a Sunday resolves back to the preceding Friday")
    void tradingDayOnOrBefore() {
        assertThat(MarketCalendar.tradingDayOnOrBefore(LocalDate.parse("2026-09-13")))
                .isEqualTo(LocalDate.parse("2026-09-11"));
    }
}
