package dev.aperture.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened when a recommendation was submitted.
 *
 * <p>Both legs are reported independently because they genuinely can differ: the entry can fill
 * while the exit is rejected, and a caller that saw only an overall "ok" would believe it holds a
 * protected position when it holds a naked one. Every field here exists so the operator can tell
 * exactly which orders are live.
 */
public record SubmissionResult(
        boolean accepted,
        String refusedReason,
        String environment,
        String accountLabel,
        Optional<LegResult> entry,
        Optional<LegResult> exit,
        List<String> notes) {

    public SubmissionResult {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(exit, "exit");
        notes = List.copyOf(notes);
        refusedReason = refusedReason == null ? "" : refusedReason;
    }

    /** Refused before anything reached the venue. */
    public static SubmissionResult refused(String reason, String environment, String account) {
        return new SubmissionResult(false, reason, environment, account,
                Optional.empty(), Optional.empty(), List.of());
    }

    /** Whether the position is currently unprotected: entry filled but no resting exit. */
    public boolean isUnprotected() {
        return entry.map(LegResult::isFilled).orElse(false)
                && exit.map(leg -> !leg.isLive()).orElse(true);
    }

    /**
     * One submitted order.
     *
     * @param clientOrderId the id Aperture generated; Webull cancels and amends by this, not by
     *     its own order id
     * @param status the vendor's last reported status, or the local failure if it never landed
     */
    public record LegResult(
            String side,
            String orderType,
            String timeInForce,
            BigDecimal quantity,
            Optional<BigDecimal> limitPrice,
            String clientOrderId,
            Optional<String> orderId,
            String status,
            Optional<BigDecimal> filledQuantity,
            Optional<BigDecimal> filledPrice,
            Optional<String> error) {

        public boolean isFilled() {
            return "FILLED".equalsIgnoreCase(status);
        }

        public boolean isPartiallyFilled() {
            return "PARTIAL_FILLED".equalsIgnoreCase(status);
        }

        /** Working at the venue: accepted and not terminal. */
        public boolean isLive() {
            return error.isEmpty()
                    && !"FAILED".equalsIgnoreCase(status)
                    && !"CANCELLED".equalsIgnoreCase(status);
        }
    }
}
