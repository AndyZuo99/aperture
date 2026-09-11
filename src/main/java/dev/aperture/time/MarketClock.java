package dev.aperture.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * The single source of "what time is it" and "is the market open".
 *
 * <p>Wraps a {@link Clock} rather than calling {@code Instant.now()} so tests can drive the
 * session state to any point in the trading day. Every component that needs the time takes this,
 * which is what makes a test asserting pre-market behaviour possible at all.
 */
public class MarketClock {

    private final Clock clock;

    public MarketClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZonedDateTime exchangeTime() {
        return now().atZone(MarketCalendar.EXCHANGE_ZONE);
    }

    /** The exchange-local date. Not the local date of whoever is running this. */
    public LocalDate today() {
        return exchangeTime().toLocalDate();
    }

    public TradingSession currentSession() {
        return sessionAt(now());
    }

    public TradingSession sessionAt(Instant instant) {
        ZonedDateTime exchange = instant.atZone(MarketCalendar.EXCHANGE_ZONE);
        LocalDate date = exchange.toLocalDate();
        if (!MarketCalendar.isTradingDay(date)) {
            return TradingSession.CLOSED;
        }
        LocalTime time = exchange.toLocalTime();
        LocalTime regularClose = MarketCalendar.regularCloseOn(date);
        LocalTime postClose = MarketCalendar.postMarketCloseOn(date);

        if (time.isBefore(MarketCalendar.PRE_MARKET_OPEN)) {
            return TradingSession.CLOSED;
        }
        if (time.isBefore(MarketCalendar.REGULAR_OPEN)) {
            return TradingSession.PRE_MARKET;
        }
        if (time.isBefore(regularClose)) {
            return TradingSession.REGULAR;
        }
        if (time.isBefore(postClose)) {
            return TradingSession.POST_MARKET;
        }
        return TradingSession.CLOSED;
    }

    public boolean isRegularHours() {
        return currentSession() == TradingSession.REGULAR;
    }

    public boolean isOpen() {
        return currentSession() != TradingSession.CLOSED;
    }

    /**
     * The session date quotes should be attributed to: today if today is a trading day, otherwise
     * the most recent one. A quote arriving at 2am Saturday belongs to Friday's session.
     */
    public LocalDate currentTradingDate() {
        return MarketCalendar.tradingDayOnOrBefore(today());
    }
}
