package dev.aperture.analysis;

import dev.aperture.marketdata.Bar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A selectable window for the historical chart.
 *
 * <p>Each range picks its own bar granularity rather than always using daily bars. A week of daily
 * closes is five points, which is not a chart - it is a line between two prices. Thirty-minute
 * bars over the same week give around sixty-five, which actually shows what happened.
 *
 * <p>The consequence is that short ranges are intraday and therefore not corporate-action
 * adjusted. That is not a gap: the vendor's intraday series is already adjusted, and a split
 * inside a one-week window would be visible rather than hidden. The chart labels the basis it is
 * on either way, so the two are never confused.
 */
public enum ChartRange {

    WEEK("1W", "One week", "M30", 120, false, 7),
    MONTH("1M", "One month", "D", 21, true, 0),
    QUARTER("3M", "Three months", "D", 63, true, 0),
    YEAR("1Y", "One year", "D", 252, true, 0),

    /**
     * The longest history the vendor will serve.
     *
     * <p>Capped at 1,200 sessions - about four years and ten months - because
     * {@code getBatchBars} refuses anything more: "The count ranges is 1 to 1200." Labelled 5Y
     * because that is what it is reaching for, and the chart shows the actual first date so the
     * shortfall is visible rather than implied.
     */
    FIVE_YEAR("5Y", "Five years (vendor maximum, 1200 sessions)", "D", 1200, true, 0);

    /** The vendor's hard ceiling on bars per request. */
    public static final int MAX_BARS = 1200;

    private final String label;
    private final String description;
    private final String timespan;
    private final int bars;
    private final boolean daily;
    private final int calendarDays;

    ChartRange(String label, String description, String timespan, int bars, boolean daily,
               int calendarDays) {
        this.label = label;
        this.description = description;
        this.timespan = timespan;
        this.bars = bars;
        this.daily = daily;
        this.calendarDays = calendarDays;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The vendor timespan code: {@code D} for daily, {@code M30} for thirty-minute bars. */
    public String timespan() {
        return timespan;
    }

    public int bars() {
        return bars;
    }

    /**
     * Whether this range uses daily bars, and so can carry a corporate-action adjustment.
     *
     * <p>Intraday ranges cannot: the adjuster works on session dates, and applying a split factor
     * to bars within a single day has no meaning.
     */
    public boolean isDaily() {
        return daily;
    }

    /**
     * Trims an intraday series to the range's actual calendar window.
     *
     * <p>Bars per session vary with the venue and with extended hours, so a fixed bar count is
     * only an approximation of a time window - asking for 120 thirty-minute bars returned eleven
     * days of data under a button labelled "1W". The count is deliberately generous and the series
     * is then cut to the window it claims to show.
     *
     * <p>Daily ranges need none of this: one bar is one session, so the count <em>is</em> the
     * window.
     */
    public List<Bar> trim(List<Bar> bars) {
        if (calendarDays <= 0 || bars.isEmpty()) {
            return bars;
        }
        LocalDate cutoff = bars.get(bars.size() - 1).sessionDate().minusDays(calendarDays);
        List<Bar> trimmed = new ArrayList<>();
        for (Bar bar : bars) {
            if (bar.sessionDate().isAfter(cutoff)) {
                trimmed.add(bar);
            }
        }
        return trimmed.isEmpty() ? bars : trimmed;
    }

    public static ChartRange parse(String value) {
        if (value == null || value.isBlank()) {
            return YEAR;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        for (ChartRange range : values()) {
            if (range.label.equals(normalised) || range.name().equals(normalised)) {
                return range;
            }
        }
        throw new IllegalArgumentException("Unknown range: " + value
                + ". Expected one of 1W, 1M, 3M, 1Y or 5Y.");
    }
}
