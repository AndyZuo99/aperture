package dev.aperture.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.instrument.SecurityType;
import dev.aperture.time.TradingSession;
import dev.aperture.time.MarketClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Quotes for instruments that are not streamed.
 *
 * <p>Only the watchlist is subscribed, which is fine until something asks about a symbol outside
 * it. The recommender ranks a candidate pool of around a hundred names and then shortlists a
 * handful to price - and a cache-only lookup returns nothing for every one of them, so the model
 * sees no bid or ask and declines to propose names it cannot price. The pool being wider than the
 * watchlist is the entire point of having a pool.
 */
class OnDemandQuotesTest {

    private static final Instant NOW = Instant.parse("2026-09-14T14:30:00Z");

    /** Movable, because "the cached quote has gone stale" is otherwise unreachable. */
    private Instant now = NOW;

    private ReferenceDataService referenceData;
    private WebullQuoteClient rest;
    private WebullClientHolder holder;
    private MarketDataService marketData;

    @BeforeEach
    void setUp() {
        referenceData = mock(ReferenceDataService.class);
        rest = mock(WebullQuoteClient.class);
        holder = mock(WebullClientHolder.class);
        now = NOW;
        MarketClock clock = mock(MarketClock.class);
        when(clock.now()).thenAnswer(invocation -> now);
        when(holder.isConnected()).thenReturn(true);
        when(rest.isEntitlementMissing()).thenReturn(false);

        marketData = new MarketDataService(
                referenceData, mock(WebullStreamingQuoteSource.class), rest,
                mock(SimulatedQuoteSource.class), mock(WebullInstrumentClient.class),
                mock(WatchlistService.class), holder, clock, properties());
    }

    @Test
    @DisplayName("a symbol outside the watchlist is fetched rather than skipped")
    void fetchesSymbolsThatAreNotStreamed() {
        // The exact failure: MU ranked above every watchlisted name and came back unpriceable.
        known("MU");
        when(rest.quotes(anyList())).thenReturn(Map.of(id("MU"), quote("MU", 916.70, 916.80)));

        Map<String, Quote> quotes = marketData.quotesOnDemand(List.of("MU"));

        assertThat(quotes).containsOnlyKeys("MU");
        assertThat(quotes.get("MU").bid()).isEqualTo(Price.of(916.70));
        assertThat(quotes.get("MU").ask()).isEqualTo(Price.of(916.80));
    }

    @Test
    @DisplayName("a shortlist costs one vendor call, not one per symbol")
    void batchesTheWholeShortlist() {
        // getSnapshots takes up to a hundred symbols. Six round trips for six names is the kind
        // of thing that is invisible until a run times out.
        List<String> shortlist = List.of("MU", "KLAC", "PSX", "HAL", "SLB", "XLE");
        shortlist.forEach(this::known);
        when(rest.quotes(anyList())).thenReturn(Map.of());

        marketData.quotesOnDemand(shortlist);

        ArgumentCaptor<List<Instrument>> captor = ArgumentCaptor.captor();
        verify(rest, times(1)).quotes(captor.capture());
        assertThat(captor.getValue()).extracting(Instrument::primarySymbol)
                .containsExactlyElementsOf(shortlist);
    }

    @Test
    @DisplayName("a fresh streamed quote is used as-is, with no vendor call at all")
    void freshCachedQuotesAreNotRefetched() {
        // A shortlist that happens to be watchlisted should cost nothing.
        known("AAPL");
        when(rest.quotes(anyList())).thenReturn(Map.of(id("AAPL"), quote("AAPL", 332.90, 332.96)));
        marketData.quotesOnDemand(List.of("AAPL"));
        clearInvocations(rest);

        Map<String, Quote> quotes = marketData.quotesOnDemand(List.of("AAPL"));

        assertThat(quotes).containsOnlyKeys("AAPL");
        verify(rest, never()).quotes(anyList());
    }

    @Test
    @DisplayName("only the misses are fetched when a shortlist is mixed")
    void fetchesOnlyWhatIsMissing() {
        known("AAPL");
        known("MU");
        when(rest.quotes(anyList())).thenReturn(Map.of(id("AAPL"), quote("AAPL", 332.90, 332.96)));
        marketData.quotesOnDemand(List.of("AAPL"));
        clearInvocations(rest);
        when(rest.quotes(anyList())).thenReturn(Map.of(id("MU"), quote("MU", 916.70, 916.80)));

        Map<String, Quote> quotes = marketData.quotesOnDemand(List.of("AAPL", "MU"));

        assertThat(quotes).containsOnlyKeys("AAPL", "MU");
        ArgumentCaptor<List<Instrument>> captor = ArgumentCaptor.captor();
        verify(rest).quotes(captor.capture());
        assertThat(captor.getValue()).extracting(Instrument::primarySymbol)
                .containsExactly("MU");
    }

    @Test
    @DisplayName("an unresolvable symbol is dropped, not sent to the vendor")
    void unknownSymbolsAreNotFetched() {
        when(referenceData.resolve("NOPE")).thenReturn(Optional.empty());

        assertThat(marketData.quotesOnDemand(List.of("NOPE"))).isEmpty();
        verify(rest, never()).quotes(anyList());
    }

    @Test
    @DisplayName("a vendor failure yields no quote rather than propagating")
    void vendorFailureIsContained() {
        // One bad symbol in a shortlist must not fail the whole recommendation run.
        known("MU");
        when(rest.quotes(anyList())).thenThrow(new RuntimeException("417 ILLEGAL_PARAMETER"));

        assertThat(marketData.quotesOnDemand(List.of("MU"))).isEmpty();
    }

    @Test
    @DisplayName("a stale quote is refreshed, and survives if the refresh cannot happen")
    void staleQuotesAreRefreshedOrKept() {
        known("AAPL");
        when(rest.quotes(anyList())).thenReturn(Map.of(id("AAPL"), quote("AAPL", 332.90, 332.96)));
        marketData.quotesOnDemand(List.of("AAPL"));

        now = NOW.plusSeconds(600);        // well past the staleness window
        clearInvocations(rest);
        marketData.quotesOnDemand(List.of("AAPL"));
        verify(rest).quotes(anyList());    // stale, so it is re-fetched

        // Disconnected, the stale quote is served rather than nothing: it is still a market
        // observation, and it carries its own age for the caller to judge.
        when(holder.isConnected()).thenReturn(false);
        clearInvocations(rest);

        assertThat(marketData.quotesOnDemand(List.of("AAPL"))).containsOnlyKeys("AAPL");
        verify(rest, never()).quotes(anyList());
    }

    @Test
    @DisplayName("the single-symbol form still works, through the same path")
    void singleSymbolFormDelegates() {
        known("MU");
        when(rest.quotes(anyList())).thenReturn(Map.of(id("MU"), quote("MU", 916.70, 916.80)));

        assertThat(marketData.quoteOnDemand("MU"))
                .hasValueSatisfying(q -> assertThat(q.ask()).isEqualTo(Price.of(916.80)));
        assertThat(marketData.quoteOnDemand("NOPE")).isEmpty();
    }

    private void known(String symbol) {
        Instrument instrument = new Instrument(id(symbol), symbol, symbol,
                SecurityType.COMMON_STOCK, "NASDAQ", "USD", Optional.empty(), true);
        when(referenceData.resolve(symbol)).thenReturn(Optional.of(instrument));
    }

    private static InstrumentId id(String symbol) {
        return InstrumentId.of(symbol);
    }

    private static Quote quote(String symbol, double bid, double ask) {
        return new Quote(id(symbol), Price.of(bid), Quantity.of(100), Price.of(ask),
                Quantity.of(100), Price.of(ask), Quantity.of(1_000_000L), Price.of(bid),
                TradingSession.REGULAR, QuoteProvenance.LIVE_REST, NOW, NOW);
    }

    private static ApertureProperties properties() {
        return new ApertureProperties(
                new ApertureProperties.Webull("k", "s", "", "", "us", true, "SANDBOX",
                        false, "", Duration.ofMinutes(6)),
                new ApertureProperties.MarketData(List.of(), List.of(), List.of(), List.of(),
                        List.of(), Duration.ofSeconds(2), Duration.ofSeconds(15), 250, false,
                        Duration.ofMinutes(5)),
                new ApertureProperties.Analyst(false, "m", "", 8, 16000));
    }
}
