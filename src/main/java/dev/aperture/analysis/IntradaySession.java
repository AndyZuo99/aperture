package dev.aperture.analysis;

import dev.aperture.marketdata.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One trading day, summarised the way a day trader reads it.
 *
 * <p>Different questions from the historical view. A one-year chart is about whether a position is
 * worth holding; a session is about where price is <em>right now</em> relative to today's open,
 * today's range, and the volume-weighted average everyone else has been paying.
 *
 * @param vwap volume-weighted average price for the session, the usual reference for whether the
 *     current price is expensive or cheap against where the day's volume actually traded
 * @param rangePosition where the last price sits between the session low and high, 0 to 100 - a
 *     price at 95 is pressing the highs whatever the percentage change says
 */
public record IntradaySession(
        String symbol,
        LocalDate sessionDate,
        List<Bar> bars,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal last,
        BigDecimal volume,
        Optional<BigDecimal> vwap,
        Optional<BigDecimal> previousClose,
        BigDecimal changeFromOpen,
        BigDecimal changeFromOpenPercent,
        Optional<BigDecimal> changeFromPreviousClose,
        Optional<BigDecimal> changeFromPreviousClosePercent,
        BigDecimal rangePosition) {

    public IntradaySession {
        Objects.requireNonNull(symbol, "symbol");
        bars = List.copyOf(bars);
    }

    /**
     * Builds a session from intraday bars, keeping only the most recent session date.
     *
     * <p>The vendor returns a rolling window that spans several days, so the bars have to be
     * trimmed to one session - otherwise the "day range" quietly becomes a two-day range and the
     * open is yesterday's.
     *
     * @param previousClose the prior session's close, for the change figure a quote screen shows
     */
    public static Optional<IntradaySession> from(String symbol, List<Bar> intradayBars,
                                                 BigDecimal previousClose) {
        if (intradayBars == null || intradayBars.isEmpty()) {
            return Optional.empty();
        }
        LocalDate latest = intradayBars.stream()
                .map(Bar::sessionDate)
                .max(LocalDate::compareTo)
                .orElseThrow();

        List<Bar> session = new ArrayList<>();
        for (Bar bar : intradayBars) {
            if (bar.sessionDate().equals(latest)) {
                session.add(bar);
            }
        }
        if (session.isEmpty()) {
            return Optional.empty();
        }
        session.sort((a, b) -> a.startTime().compareTo(b.startTime()));

        BigDecimal open = session.get(0).open().value();
        BigDecimal last = session.get(session.size() - 1).close().value();
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;

        for (Bar bar : session) {
            BigDecimal barHigh = bar.high().value();
            BigDecimal barLow = bar.low().value();
            if (high == null || barHigh.compareTo(high) > 0) {
                high = barHigh;
            }
            if (low == null || barLow.compareTo(low) < 0) {
                low = barLow;
            }
            BigDecimal barVolume = bar.volume().value();
            volume = volume.add(barVolume);
            // Typical price rather than close: a bar that opened low and closed high did not trade
            // all its volume at the close.
            BigDecimal typical = barHigh.add(barLow).add(bar.close().value())
                    .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_EVEN);
            notional = notional.add(typical.multiply(barVolume));
        }

        Optional<BigDecimal> vwap = volume.signum() > 0
                ? Optional.of(notional.divide(volume, 4, RoundingMode.HALF_EVEN))
                : Optional.empty();

        BigDecimal changeFromOpen = last.subtract(open);
        BigDecimal changeFromOpenPercent = open.signum() > 0
                ? changeFromOpen.multiply(BigDecimal.valueOf(100))
                        .divide(open, 2, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO;

        Optional<BigDecimal> prior = previousClose != null && previousClose.signum() > 0
                ? Optional.of(previousClose) : Optional.empty();
        Optional<BigDecimal> changeFromPrior = prior.map(last::subtract);
        Optional<BigDecimal> changeFromPriorPercent = prior.map(value ->
                last.subtract(value).multiply(BigDecimal.valueOf(100))
                        .divide(value, 2, RoundingMode.HALF_EVEN));

        // Where in the day's range the last trade sits. A flat range would divide by zero, and
        // "the middle" is the honest answer when high equals low.
        BigDecimal span = high.subtract(low);
        BigDecimal rangePosition = span.signum() > 0
                ? last.subtract(low).multiply(BigDecimal.valueOf(100))
                        .divide(span, 1, RoundingMode.HALF_EVEN)
                : BigDecimal.valueOf(50);

        return Optional.of(new IntradaySession(
                symbol, latest, session,
                scale(open), scale(high), scale(low), scale(last),
                volume.setScale(0, RoundingMode.HALF_EVEN),
                vwap.map(IntradaySession::scale),
                prior.map(IntradaySession::scale),
                scale(changeFromOpen), changeFromOpenPercent,
                changeFromPrior.map(IntradaySession::scale), changeFromPriorPercent,
                rangePosition));
    }

    /** Whether the last price is above the session's volume-weighted average. */
    public Optional<Boolean> isAboveVwap() {
        return vwap.map(value -> last.compareTo(value) > 0);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
