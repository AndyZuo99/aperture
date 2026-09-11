package dev.aperture.corporate;

import java.time.Instant;
import java.util.Objects;

/** A corporate action together with where it came from and when Aperture learned of it. */
public record RecordedAction(CorporateAction action, ActionSource source, Instant recordedAt) {

    public RecordedAction {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /**
     * Identity for de-duplication: the same split learned from two sources is one action.
     *
     * <p>Keyed on instrument, ex-date and description rather than on the record itself, so that a
     * vendor dividend and a manually declared one for the same date collapse instead of being
     * applied twice - which would square the adjustment.
     */
    public String dedupeKey() {
        return action.instrumentId().value() + "|" + action.exDate() + "|" + action.describe();
    }
}
