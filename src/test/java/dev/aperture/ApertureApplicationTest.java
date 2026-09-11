package dev.aperture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.ai.MarketAnalyst;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.QuoteProvenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole graph wires, with no credentials of any kind.
 *
 * <p>This is the test that proves someone can clone the repository and run it. Every external
 * dependency - Webull, Anthropic - must degrade to something working rather than failing
 * startup, and this asserts that rather than assuming it.
 */
@SpringBootTest
class ApertureApplicationTest {

    @Autowired
    private MarketDataService marketData;

    @Autowired
    private CorporateActionService corporateActions;

    @Autowired
    private MarketAnalyst analyst;

    @Test
    @DisplayName("the context starts without Webull or Anthropic credentials")
    void contextLoads() {
        assertThat(marketData).isNotNull();
        assertThat(corporateActions).isNotNull();
        assertThat(analyst).isNotNull();
    }

    @Test
    @DisplayName("with no vendor connection the feed reports itself as simulated, not as live")
    void degradesToSimulatedAndSaysSo() {
        var status = marketData.status();

        assertThat(status.provenance()).isEqualTo(QuoteProvenance.SIMULATED);
        assertThat(status.credentialsPresent()).isFalse();
        assertThat(status.showSimulatedWarning()).isTrue();
        // The distinction that matters: the UI must never be able to render this as market data.
        assertThat(status.provenance().isLive()).isFalse();
    }

    @Test
    @DisplayName("the analyst is absent rather than broken when it has no API key")
    void analystIsUnavailableNotBroken() {
        assertThat(analyst.isAvailable()).isFalse();

        var result = analyst.analyse("What is AAPL doing?");
        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("reference splits are seeded and persisted through the repository")
    void referenceActionsAreSeeded() {
        // Seven known historical splits for the default watchlist, loaded through Flyway's
        // schema and the JPA repository - so this also proves the persistence round-trip.
        assertThat(corporateActions.count()).isGreaterThanOrEqualTo(7);
        assertThat(corporateActions.all())
                .anySatisfy(recorded ->
                        assertThat(recorded.action().describe()).isEqualTo("Split 10-for-1"));
    }
}
