package dev.aperture.corporate;

import dev.aperture.marketdata.Bar;
import java.util.List;
import java.util.Objects;

/**
 * A price series together with an honest statement of what it actually is.
 *
 * <p>The requested policy and the delivered basis are separate fields because they can legitimately
 * differ. Asking for raw prints from a feed that only publishes split-adjusted bars cannot be
 * satisfied, and the right answer is to return what exists and say so - not to silently return
 * something else under the requested label, and not to fail.
 *
 * @param satisfied whether the delivered basis is the one that was asked for
 * @param note human-readable explanation, shown in the UI when it is not
 */
public record AdjustedSeries(
        List<Bar> bars,
        AdjustmentPolicy requestedPolicy,
        PriceBasis basis,
        boolean satisfied,
        String note) {

    public AdjustedSeries {
        Objects.requireNonNull(requestedPolicy, "requestedPolicy");
        Objects.requireNonNull(basis, "basis");
        bars = List.copyOf(bars);
        note = note == null ? "" : note;
    }

    public static AdjustedSeries empty(AdjustmentPolicy policy) {
        return new AdjustedSeries(List.of(), policy, PriceBasis.forPolicy(policy), true,
                "No price history available.");
    }

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    public int size() {
        return bars.size();
    }
}
