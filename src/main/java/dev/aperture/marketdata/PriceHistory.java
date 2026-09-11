package dev.aperture.marketdata;

import dev.aperture.corporate.AdjustedSeries;
import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.corporate.PriceAdjuster;
import dev.aperture.instrument.InstrumentId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Daily bar history, stored raw and adjusted on read.
 *
 * <p>Storing the raw series and applying corporate actions at read time - rather than storing a
 * pre-adjusted series - is the important choice here. A stored adjusted series has to be rewritten
 * every time a new action appears, and if that rewrite is ever missed the stored data is silently
 * wrong with nothing to compare against. Raw bars are immutable facts; the adjustment is a view.
 */
@Service
public class PriceHistory {

    private final CorporateActionService corporateActions;
    private final Map<InstrumentId, List<Bar>> raw = new ConcurrentHashMap<>();

    public PriceHistory(CorporateActionService corporateActions) {
        this.corporateActions = corporateActions;
    }

    /** Replaces the stored history for an instrument. Bars are kept ascending by date. */
    public void store(InstrumentId instrumentId, List<Bar> bars) {
        if (bars.isEmpty()) {
            return;
        }
        List<Bar> sorted = bars.stream()
                .sorted(java.util.Comparator.comparing(Bar::sessionDate))
                .toList();
        raw.put(instrumentId, sorted);
    }

    public List<Bar> rawBars(InstrumentId instrumentId) {
        return raw.getOrDefault(instrumentId, List.of());
    }

    /** The series restated under the given policy, with the basis actually delivered. */
    public AdjustedSeries series(InstrumentId instrumentId, AdjustmentPolicy policy) {
        List<Bar> bars = rawBars(instrumentId);
        if (bars.isEmpty()) {
            return AdjustedSeries.empty(policy);
        }
        return PriceAdjuster.adjust(bars, corporateActions.actionsFor(instrumentId), policy);
    }

    /** Just the bars, for callers that have already established the basis. */
    public List<Bar> bars(InstrumentId instrumentId, AdjustmentPolicy policy) {
        return series(instrumentId, policy).bars();
    }

    /** The most recent {@code limit} sessions, keeping the basis metadata. */
    public AdjustedSeries recentSeries(InstrumentId instrumentId, AdjustmentPolicy policy,
                                       int limit) {
        AdjustedSeries full = series(instrumentId, policy);
        if (full.size() <= limit) {
            return full;
        }
        List<Bar> bars = full.bars();
        return new AdjustedSeries(bars.subList(bars.size() - limit, bars.size()),
                full.requestedPolicy(), full.basis(), full.satisfied(), full.note());
    }

    public List<Bar> recentBars(InstrumentId instrumentId, AdjustmentPolicy policy, int limit) {
        return recentSeries(instrumentId, policy, limit).bars();
    }

    public Optional<Bar> latestBar(InstrumentId instrumentId, AdjustmentPolicy policy) {
        List<Bar> bars = bars(instrumentId, policy);
        return bars.isEmpty() ? Optional.empty() : Optional.of(bars.get(bars.size() - 1));
    }

    public boolean hasHistory(InstrumentId instrumentId) {
        return !rawBars(instrumentId).isEmpty();
    }

    public int instrumentCount() {
        return raw.size();
    }

    /**
     * Total return over the stored window, as a percentage.
     *
     * <p>Takes a policy so the caller states which question they are asking. Computed on
     * {@link AdjustmentPolicy#NONE} across a split this returns a number like -90%, which is the
     * headline failure this whole package prevents.
     */
    public Optional<BigDecimal> returnOverWindow(InstrumentId instrumentId,
                                                 AdjustmentPolicy policy) {
        List<Bar> bars = bars(instrumentId, policy);
        if (bars.size() < 2) {
            return Optional.empty();
        }
        BigDecimal first = bars.get(0).close().value();
        BigDecimal last = bars.get(bars.size() - 1).close().value();
        if (first.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(last.subtract(first)
                .multiply(BigDecimal.valueOf(100))
                .divide(first, 2, RoundingMode.HALF_EVEN));
    }

    /**
     * Annualised volatility of daily log returns, in percent.
     *
     * <p>Always computed on an adjusted series: a split in the window would otherwise contribute a
     * single -90% "return" and dominate the estimate entirely.
     */
    public Optional<BigDecimal> annualisedVolatility(InstrumentId instrumentId) {
        List<Bar> bars = bars(instrumentId, AdjustmentPolicy.SPLITS_ONLY);
        if (bars.size() < 20) {
            return Optional.empty();
        }
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        for (int i = 1; i < bars.size(); i++) {
            double previous = bars.get(i - 1).close().value().doubleValue();
            double current = bars.get(i).close().value().doubleValue();
            if (previous <= 0 || current <= 0) {
                continue;
            }
            double logReturn = Math.log(current / previous);
            sum += logReturn;
            sumSquares += logReturn * logReturn;
            count++;
        }
        if (count < 2) {
            return Optional.empty();
        }
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        if (variance <= 0) {
            return Optional.empty();
        }
        double annualised = Math.sqrt(variance) * Math.sqrt(252) * 100;
        return Optional.of(BigDecimal.valueOf(annualised).setScale(2, RoundingMode.HALF_EVEN));
    }

    /** Average daily volume over the stored window, used as a liquidity reference. */
    public Optional<BigDecimal> averageDailyVolume(InstrumentId instrumentId) {
        List<Bar> bars = bars(instrumentId, AdjustmentPolicy.SPLITS_ONLY);
        if (bars.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = bars.stream()
                .map(bar -> bar.volume().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(total.divide(BigDecimal.valueOf(bars.size()), 0, RoundingMode.HALF_EVEN));
    }

    public Optional<LocalDate> earliestSession(InstrumentId instrumentId) {
        List<Bar> bars = rawBars(instrumentId);
        return bars.isEmpty() ? Optional.empty() : Optional.of(bars.get(0).sessionDate());
    }
}
