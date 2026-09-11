package dev.aperture.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Set;

/**
 * The NYSE/Nasdaq trading calendar: weekends, holidays, and early closes.
 *
 * <p>Holidays are computed from their rules rather than listed as dates, so the calendar does not
 * expire at the end of a hard-coded table. The rules are the SIFMA/NYSE definitions: fixed-date
 * holidays observed on the nearest weekday, and floating holidays on an nth-weekday rule.
 */
public final class MarketCalendar {

    public static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");

    public static final LocalTime PRE_MARKET_OPEN = LocalTime.of(4, 0);
    public static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    public static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    public static final LocalTime POST_MARKET_CLOSE = LocalTime.of(20, 0);

    /** On half days the regular session ends at 13:00 and post-market at 17:00. */
    public static final LocalTime EARLY_REGULAR_CLOSE = LocalTime.of(13, 0);
    public static final LocalTime EARLY_POST_MARKET_CLOSE = LocalTime.of(17, 0);

    private MarketCalendar() {
    }

    public static boolean isTradingDay(LocalDate date) {
        return !isWeekend(date) && !isHoliday(date);
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public static boolean isHoliday(LocalDate date) {
        return holidays(date.getYear()).contains(date);
    }

    /**
     * Half days: the Friday after Thanksgiving, Christmas Eve when it falls on a weekday, and
     * July 3rd when Independence Day is observed on the 4th and that is a weekday.
     */
    public static boolean isEarlyClose(LocalDate date) {
        if (!isTradingDay(date)) {
            return false;
        }
        LocalDate thanksgiving = nthWeekdayOf(date.getYear(), Month.NOVEMBER, DayOfWeek.THURSDAY, 4);
        if (date.equals(thanksgiving.plusDays(1))) {
            return true;
        }
        LocalDate christmasEve = LocalDate.of(date.getYear(), Month.DECEMBER, 24);
        if (date.equals(christmasEve) && !isWeekend(christmasEve)) {
            return true;
        }
        LocalDate julyThird = LocalDate.of(date.getYear(), Month.JULY, 3);
        return date.equals(julyThird) && !isWeekend(julyThird);
    }

    public static LocalTime regularCloseOn(LocalDate date) {
        return isEarlyClose(date) ? EARLY_REGULAR_CLOSE : REGULAR_CLOSE;
    }

    public static LocalTime postMarketCloseOn(LocalDate date) {
        return isEarlyClose(date) ? EARLY_POST_MARKET_CLOSE : POST_MARKET_CLOSE;
    }

    public static LocalDate nextTradingDay(LocalDate from) {
        LocalDate candidate = from.plusDays(1);
        while (!isTradingDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public static LocalDate previousTradingDay(LocalDate from) {
        LocalDate candidate = from.minusDays(1);
        while (!isTradingDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /** The most recent trading day on or before {@code from}. */
    public static LocalDate tradingDayOnOrBefore(LocalDate from) {
        LocalDate candidate = from;
        while (!isTradingDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    public static Set<LocalDate> holidays(int year) {
        Set<LocalDate> dates = new HashSet<>();
        dates.add(observed(LocalDate.of(year, Month.JANUARY, 1)));
        dates.add(nthWeekdayOf(year, Month.JANUARY, DayOfWeek.MONDAY, 3));      // MLK Day
        dates.add(nthWeekdayOf(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));     // Presidents' Day
        dates.add(goodFriday(year));
        dates.add(lastWeekdayOf(year, Month.MAY, DayOfWeek.MONDAY));            // Memorial Day
        if (year >= 2022) {
            dates.add(observed(LocalDate.of(year, Month.JUNE, 19)));            // Juneteenth
        }
        dates.add(observed(LocalDate.of(year, Month.JULY, 4)));
        dates.add(nthWeekdayOf(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));    // Labor Day
        dates.add(nthWeekdayOf(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));   // Thanksgiving
        dates.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));
        return dates;
    }

    /**
     * A fixed-date holiday falling on a Saturday is observed the Friday before; on a Sunday, the
     * Monday after.
     */
    private static LocalDate observed(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1);
            case SUNDAY -> date.plusDays(1);
            default -> date;
        };
    }

    private static LocalDate nthWeekdayOf(int year, Month month, DayOfWeek weekday, int n) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(n, weekday));
    }

    private static LocalDate lastWeekdayOf(int year, Month month, DayOfWeek weekday) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(weekday));
    }

    /**
     * Good Friday is the Friday before Easter Sunday, which uses the anonymous Gregorian
     * algorithm (Meeus/Jones/Butcher). It is the one market holiday with no fixed or nth-weekday
     * rule.
     */
    private static LocalDate goodFriday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2);
    }
}
