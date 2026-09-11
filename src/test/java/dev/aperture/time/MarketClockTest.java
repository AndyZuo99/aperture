package dev.aperture.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Session boundaries.
 *
 * <p>These are the assertions a fixed {@link Clock} exists for - proving the 09:29/09:31 boundary
 * behaves without waiting for 09:30 to come round.
 */
class MarketClockTest {

    /** A Thursday, and an ordinary full session. */
    private static final LocalDate TRADING_DAY = LocalDate.parse("2026-09-10");

    @Test
    @DisplayName("before 04:00 the market is closed")
    void beforePreMarket() {
        assertThat(sessionAt(TRADING_DAY, 3, 59)).isEqualTo(TradingSession.CLOSED);
    }

    @Test
    @DisplayName("04:00 opens the pre-market")
    void preMarketOpens() {
        assertThat(sessionAt(TRADING_DAY, 4, 0)).isEqualTo(TradingSession.PRE_MARKET);
        assertThat(sessionAt(TRADING_DAY, 9, 29)).isEqualTo(TradingSession.PRE_MARKET);
    }

    @Test
    @DisplayName("09:30 opens the regular session")
    void regularOpens() {
        assertThat(sessionAt(TRADING_DAY, 9, 30)).isEqualTo(TradingSession.REGULAR);
        assertThat(sessionAt(TRADING_DAY, 15, 59)).isEqualTo(TradingSession.REGULAR);
    }

    @Test
    @DisplayName("16:00 ends the regular session and starts post-market")
    void postMarket() {
        assertThat(sessionAt(TRADING_DAY, 16, 0)).isEqualTo(TradingSession.POST_MARKET);
        assertThat(sessionAt(TRADING_DAY, 19, 59)).isEqualTo(TradingSession.POST_MARKET);
    }

    @Test
    @DisplayName("20:00 closes everything")
    void afterPostMarket() {
        assertThat(sessionAt(TRADING_DAY, 20, 0)).isEqualTo(TradingSession.CLOSED);
    }

    @Test
    @DisplayName("a weekend is closed at every hour")
    void weekendIsClosed() {
        assertThat(sessionAt(LocalDate.parse("2026-09-12"), 11, 0))  // Saturday
                .isEqualTo(TradingSession.CLOSED);
        assertThat(sessionAt(LocalDate.parse("2026-09-13"), 11, 0))  // Sunday
                .isEqualTo(TradingSession.CLOSED);
    }

    @Test
    @DisplayName("a half day closes the regular session at 13:00")
    void halfDay() {
        LocalDate blackFriday = LocalDate.parse("2026-11-27");
        assertThat(sessionAt(blackFriday, 12, 59)).isEqualTo(TradingSession.REGULAR);
        assertThat(sessionAt(blackFriday, 13, 0)).isEqualTo(TradingSession.POST_MARKET);
        assertThat(sessionAt(blackFriday, 17, 0)).isEqualTo(TradingSession.CLOSED);
    }

    @Test
    @DisplayName("session is computed in exchange time, not the host's zone")
    void usesExchangeZone() {
        // 13:00 UTC is 09:00 in New York: pre-market, not the regular session. A clock that used
        // the host's zone would answer differently depending on where it ran.
        Instant thirteenHundredUtc = LocalDateTime.of(TRADING_DAY, LocalTime.of(13, 0))
                .toInstant(ZoneOffset.UTC);
        MarketClock clock = new MarketClock(Clock.fixed(thirteenHundredUtc, ZoneOffset.UTC));
        assertThat(clock.currentSession()).isEqualTo(TradingSession.PRE_MARKET);
    }

    @Test
    @DisplayName("a weekend quote is attributed to the preceding Friday's session")
    void currentTradingDateOnAWeekend() {
        Instant sunday = ZonedDateTime.of(LocalDate.parse("2026-09-13"), LocalTime.of(2, 0),
                MarketCalendar.EXCHANGE_ZONE).toInstant();
        MarketClock clock = new MarketClock(Clock.fixed(sunday, ZoneOffset.UTC));
        assertThat(clock.currentTradingDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    }

    /**
     * Builds an instant from a wall-clock time <em>in exchange local time</em>.
     *
     * <p>Uses the zone rather than a fixed offset deliberately. A hard-coded -04:00 is EDT and
     * silently becomes wrong for any date outside daylight saving - the November half-day test
     * lands an hour early and reports the regular session still open.
     */
    private static TradingSession sessionAt(LocalDate date, int hour, int minute) {
        Instant instant = ZonedDateTime.of(date, LocalTime.of(hour, minute),
                MarketCalendar.EXCHANGE_ZONE).toInstant();
        return new MarketClock(Clock.fixed(instant, ZoneOffset.UTC)).currentSession();
    }
}
