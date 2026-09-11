package dev.aperture.marketdata;

import dev.aperture.instrument.InstrumentId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Somewhere quotes come from.
 *
 * <p>Read-only by construction: there is no method here that submits, cancels or mutates
 * anything. That is what lets the LLM analyst be handed a quote source directly without a
 * separate permission layer - the type simply cannot do anything dangerous.
 */
public interface QuoteSource {

    /** Short identifier used in logs and in the UI's data-source badge. */
    String sourceName();

    /** What a quote from this source should be labelled as. */
    QuoteProvenance provenance();

    /**
     * Whether this source is currently able to serve quotes.
     *
     * <p>Distinct from "configured" and from "connected": a Webull client can be authenticated
     * and connected while the market-data entitlement is refused, which serves no quotes at all.
     */
    boolean isAvailable();

    Optional<Quote> latest(InstrumentId instrumentId);

    Map<InstrumentId, Quote> snapshot(Set<InstrumentId> instrumentIds);
}
