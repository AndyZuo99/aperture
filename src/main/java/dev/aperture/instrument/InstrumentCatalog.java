package dev.aperture.instrument;

import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.marketdata.WebullInstrumentClient;
import dev.aperture.time.MarketClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The cached tradable universe for each asset class.
 *
 * <p>Cached hard, for two reasons. These lists are large - a thousand equities, a couple of
 * thousand futures contracts - and they change roughly daily, so re-fetching per page load would
 * be pure waste. More importantly the vendor returns {@code 429 TOO_MANY_REQUESTS} after only a
 * handful of rapid calls, and the event universe alone costs one request per series: an uncached
 * catalog would rate-limit the quote feed out from under itself.
 *
 * <p>Deliberately <strong>no fabricated fallback</strong>. Elsewhere Aperture substitutes
 * simulated prices when the vendor is unreachable, because a labelled fake price still lets you
 * exercise the console. A fake list of tradable instruments is different in kind: it would assert
 * that an account can trade something, which is a claim about entitlements rather than about a
 * number. With no vendor connection this returns empty and says why.
 */
@Service
public class InstrumentCatalog {

    private static final Logger log = LoggerFactory.getLogger(InstrumentCatalog.class);

    /** These lists change about once a day; an hour is generous and still bounded. */
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final WebullInstrumentClient client;
    private final WebullClientHolder holder;
    private final MarketClock clock;

    private final Map<TradableUniverse, Cached> cache = new EnumMap<>(TradableUniverse.class);
    private final Map<TradableUniverse, ReentrantLock> locks =
            new EnumMap<>(TradableUniverse.class);
    /** Universes with a background refresh in flight. */
    private final java.util.Set<TradableUniverse> refreshing =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public InstrumentCatalog(WebullInstrumentClient client, WebullClientHolder holder,
                             MarketClock clock) {
        this.client = client;
        this.holder = holder;
        this.clock = clock;
        for (TradableUniverse universe : TradableUniverse.values()) {
            locks.put(universe, new ReentrantLock());
        }
    }

    /**
     * The universe's instruments, fetching on first use.
     *
     * <p>The per-universe lock means a burst of concurrent requests for a cold universe produces
     * one vendor fetch rather than one per request - which on a rate-limited API is the difference
     * between loading and failing.
     */
    public List<TradableInstrument> instruments(TradableUniverse universe) {
        Cached cached = cache.get(universe);
        if (cached != null && cached.isFresh(clock.now())) {
            return cached.instruments();
        }
        // Never fetch on the calling thread. Enumerating the event universe walks every series and
        // takes the better part of a minute under load; doing that inline means one cold request
        // hangs the UI, and concurrent requests queue behind it. The refresh is started in the
        // background and callers get what is cached - empty on a first load, which `loading`
        // reports so it is not mistaken for "nothing found".
        startRefresh(universe);
        return cached == null ? List.of() : cached.instruments();
    }

    /**
     * Blocks until the universe is loaded, up to a limit.
     *
     * <p>For callers that can afford to wait and genuinely need the data - the analyst, which
     * already runs for minutes and would otherwise reason about an empty universe. The UI must
     * never use this.
     */
    public List<TradableInstrument> instrumentsNow(TradableUniverse universe, Duration timeout) {
        Cached cached = cache.get(universe);
        if (cached != null && cached.isFresh(clock.now())) {
            return cached.instruments();
        }
        startRefresh(universe);
        Instant deadline = clock.now().plus(timeout);
        while (clock.now().isBefore(deadline)) {
            Cached current = cache.get(universe);
            if (current != null) {
                return current.instruments();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Cached current = cache.get(universe);
        return current == null ? List.of() : current.instruments();
    }

    /** Starts a background refresh unless one is already running for this universe. */
    private void startRefresh(TradableUniverse universe) {
        ReentrantLock lock = locks.get(universe);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Cached cached = cache.get(universe);
            if (cached != null && cached.isFresh(clock.now())) {
                return;
            }
            if (!refreshing.add(universe)) {
                return;
            }
        } finally {
            lock.unlock();
        }
        Thread worker = new Thread(() -> {
            try {
                List<TradableInstrument> fetched = client.fetch(universe);
                if (fetched.isEmpty() && cache.get(universe) != null) {
                    // A failed refresh must not empty a good cache. Stale instruments beat none.
                    return;
                }
                cache.put(universe, new Cached(fetched, clock.now()));
                if (!fetched.isEmpty()) {
                    log.info("Loaded {} instrument(s) for the {} universe",
                            fetched.size(), universe.label());
                }
            } catch (RuntimeException e) {
                log.warn("Could not load the {} universe: {}", universe.label(), e.getMessage());
            } finally {
                refreshing.remove(universe);
            }
        }, "catalog-" + universe.name().toLowerCase());
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A filtered, capped page of a universe.
     *
     * @param query free-text match over symbol, name and group; blank matches everything
     * @param group exact group match (a futures product class, an event series); blank matches all
     * @param tradableOnly hide instruments the vendor does not currently list as trading
     * @param limit maximum rows returned - the futures universe alone is a couple of thousand
     *     contracts and no interface wants them all at once
     */
    public Listing listing(TradableUniverse universe, String query, String group,
                           boolean tradableOnly, int limit) {
        List<TradableInstrument> all = instruments(universe);

        if (all.isEmpty()) {
            return new Listing(universe, List.of(), 0, 0, false,
                    isLoading(universe) ? "" : unavailableReason(), isLoading(universe));
        }

        List<TradableInstrument> filtered = all.stream()
                .filter(instrument -> !tradableOnly || instrument.tradable())
                // Exact group match, not a free-text one: clicking the "Energy" chip must not
                // also pull in every instrument with "energy" in its name.
                .filter(instrument -> group == null || group.isBlank()
                        || instrument.group().equalsIgnoreCase(group))
                .filter(instrument -> instrument.matches(query))
                .sorted(Comparator
                        .comparing(TradableInstrument::group)
                        .thenComparing(TradableInstrument::symbol))
                .toList();

        int capped = Math.max(1, limit);
        List<TradableInstrument> page = filtered.size() > capped
                ? filtered.subList(0, capped)
                : filtered;

        return new Listing(universe, page, filtered.size(), all.size(),
                filtered.size() > page.size(), "", false);
    }

    /** Instrument counts per group, for the grouping summary above the table. */
    public Map<String, Integer> groupCounts(TradableUniverse universe) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        instruments(universe).stream()
                .map(TradableInstrument::group)
                .sorted()
                .forEach(group -> counts.merge(group.isBlank() ? "Other" : group, 1, Integer::sum));
        return counts;
    }

    private String unavailableReason() {
        if (!holder.isConfigured()) {
            return "No Webull credentials configured, so the tradable universe cannot be listed. "
                    + "Unlike quotes, Aperture does not substitute a simulated list here - what an "
                    + "account may trade is a fact about entitlements, not a number to stand in for.";
        }
        if (!holder.isConnected()) {
            return holder.isInitialising()
                    ? "Still connecting to Webull."
                    : "Not connected to Webull: " + holder.detail().orElse("unknown reason");
        }
        return "The vendor returned no instruments for this universe.";
    }

    /** Drops every cached universe, so the next request refetches. */
    public void invalidate() {
        cache.clear();
    }

    public Optional<Instant> loadedAt(TradableUniverse universe) {
        Cached cached = cache.get(universe);
        return cached == null ? Optional.empty() : Optional.of(cached.fetchedAt());
    }

    /** One page of a universe, plus the counts needed to describe what was filtered away. */
    public record Listing(
            TradableUniverse universe,
            List<TradableInstrument> instruments,
            int matching,
            int total,
            boolean truncated,
            String unavailableReason,
            /** A first fetch is still running; empty here means "not yet", not "none". */
            boolean loading) {

        public boolean isAvailable() {
            return total > 0;
        }
    }

    /** Whether a first fetch for this universe is in flight. */
    public boolean isLoading(TradableUniverse universe) {
        return refreshing.contains(universe) && cache.get(universe) == null;
    }



    private record Cached(List<TradableInstrument> instruments, Instant fetchedAt) {
        boolean isFresh(Instant now) {
            return fetchedAt.plus(CACHE_TTL).isAfter(now);
        }
    }
}
