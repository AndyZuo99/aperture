package dev.aperture.marketdata;

import java.time.Instant;
import java.util.Optional;

/**
 * What the market-data feed is currently doing, for the UI's status bar.
 *
 * <p>Separates three things that are easy to conflate and that a trader needs told apart:
 * whether credentials exist, whether the vendor connection is up, and whether data is actually
 * arriving. A connected client with a refused entitlement satisfies the first two and delivers
 * nothing.
 */
public record FeedStatus(
        String activeSource,
        QuoteProvenance provenance,
        boolean credentialsPresent,
        boolean connected,
        boolean receivingData,
        String streamState,
        Optional<Instant> lastUpdateAt,
        Optional<String> detail,
        Optional<Integer> depthLevels) {

    /** A one-line summary for the status bar. */
    public String summary() {
        if (!credentialsPresent) {
            return "Simulated feed - no Webull credentials configured";
        }
        if (!connected) {
            return "Simulated feed - " + detail.orElse("not connected to Webull");
        }
        if (!receivingData) {
            return "Connected to Webull, no data yet - " + detail.orElse(streamState);
        }
        return provenance.description();
    }

    /** Whether the UI should show the "not real data" banner. */
    public boolean showSimulatedWarning() {
        return provenance == QuoteProvenance.SIMULATED;
    }
}
