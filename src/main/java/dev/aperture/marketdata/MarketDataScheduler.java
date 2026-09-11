package dev.aperture.marketdata;

import dev.aperture.config.ApertureProperties;
import dev.aperture.corporate.ActionSource;
import dev.aperture.corporate.CorporateActionService;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.time.MarketClock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives everything periodic: quote polling, history backfill, dividend refresh and stream
 * recovery.
 *
 * <p>Backfill completion is tracked <strong>per instrument</strong> rather than with a single
 * "done today" flag. With one flag, a symbol added after the daily run shows an empty chart until
 * tomorrow - which looks exactly like a broken feed.
 */
@Component
public class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    private final MarketDataService marketData;
    private final WebullQuoteClient rest;
    private final WebullStreamingQuoteSource streaming;
    private final WebullClientHolder holder;
    private final ReferenceDataService referenceData;
    private final PriceHistory priceHistory;
    private final CorporateActionService corporateActions;
    private final MarketClock clock;
    private final ApertureProperties.MarketData config;

    /** Instruments whose history has been loaded, and the session it was loaded for. */
    private final ConcurrentHashMap<String, LocalDate> backfilled = new ConcurrentHashMap<>();
    private final Set<String> dividendsFetched = ConcurrentHashMap.newKeySet();

    public MarketDataScheduler(MarketDataService marketData,
                               WebullQuoteClient rest,
                               WebullStreamingQuoteSource streaming,
                               WebullClientHolder holder,
                               ReferenceDataService referenceData,
                               PriceHistory priceHistory,
                               CorporateActionService corporateActions,
                               MarketClock clock,
                               ApertureProperties properties) {
        this.marketData = marketData;
        this.rest = rest;
        this.streaming = streaming;
        this.holder = holder;
        this.referenceData = referenceData;
        this.priceHistory = priceHistory;
        this.corporateActions = corporateActions;
        this.clock = clock;
        this.config = properties.marketData();
    }

    /**
     * Starts the streaming feed once the context is up.
     *
     * <p>Not in a constructor: the feed needs the whole graph wired, and a constructor that starts
     * background work makes every test that builds the bean start a network connection.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        streaming.start();
        log.info("Aperture is up. Market data will be live once the Webull handshake completes; "
                + "until then quotes are simulated and labelled as such.");
    }

    /** The main quote tick. */
    @Scheduled(fixedDelayString = "${aperture.market-data.poll-interval:2s}")
    public void pollQuotes() {
        try {
            marketData.poll();
        } catch (RuntimeException e) {
            log.warn("Quote poll failed: {}", e.getMessage());
        }
    }

    /**
     * Loads daily history for anything that does not have it yet.
     *
     * <p>Runs often enough to pick up a newly adopted symbol quickly, but does no work at all once
     * every instrument is current for the session.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void backfillHistory() {
        if (!holder.isConnected() || rest.isEntitlementMissing()) {
            return;
        }
        LocalDate session = clock.currentTradingDate();
        List<Instrument> pending = referenceData.tradable().stream()
                .filter(i -> !session.equals(backfilled.get(i.primarySymbol())))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        try {
            var bars = rest.dailyBars(pending, config.historyDays());
            bars.forEach(priceHistory::store);
            for (Instrument instrument : pending) {
                if (bars.containsKey(instrument.id())) {
                    backfilled.put(instrument.primarySymbol(), session);
                }
            }
            if (!bars.isEmpty()) {
                log.info("Loaded daily history for {} instrument(s)", bars.size());
            }
        } catch (RuntimeException e) {
            log.warn("History backfill failed: {}", e.getMessage());
        }
    }

    /**
     * Pulls the vendor dividend calendar.
     *
     * <p>Runs rarely: the calendar changes on the order of once a quarter per name, and it is one
     * request per symbol rather than a batch.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 20_000)
    public void refreshDividends() {
        if (!holder.isConnected()) {
            return;
        }
        Set<String> pending = new HashSet<>();
        for (Instrument instrument : referenceData.tradable()) {
            if (dividendsFetched.contains(instrument.primarySymbol())) {
                continue;
            }
            pending.add(instrument.primarySymbol());
        }
        if (pending.isEmpty()) {
            return;
        }
        int added = 0;
        for (Instrument instrument : referenceData.tradable()) {
            if (!pending.contains(instrument.primarySymbol())) {
                continue;
            }
            try {
                added += corporateActions.recordAll(rest.dividends(instrument), ActionSource.VENDOR);
                dividendsFetched.add(instrument.primarySymbol());
            } catch (RuntimeException e) {
                log.debug("Dividend fetch failed for {}: {}",
                        instrument.primarySymbol(), e.getMessage());
            }
        }
        if (added > 0) {
            log.info("Recorded {} dividend(s) from the vendor calendar", added);
        }
    }

    /** Retries a streaming feed that failed or is waiting on the handshake. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void retryStreaming() {
        try {
            streaming.retryIfDown();
        } catch (RuntimeException e) {
            log.debug("Stream retry failed: {}", e.getMessage());
        }
    }
}
