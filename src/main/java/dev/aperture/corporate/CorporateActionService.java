package dev.aperture.corporate;

import dev.aperture.common.Money;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.InstrumentId;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.persistence.CorporateActionEntity;
import dev.aperture.persistence.CorporateActionRepository;
import dev.aperture.time.MarketClock;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The register of corporate actions, and the thing that decides which ones apply to a series.
 *
 * <h2>Why there is a built-in table</h2>
 *
 * <p>Webull's corporate-action endpoint returns {@code 404} and its dictionary only ever covered
 * splits, so the vendor supplies dividends (through the dividend calendar) but not splits. Since
 * an unadjusted NVDA chart has a 90% cliff in it, Aperture ships a small table of real historical
 * splits for the default watchlist, clearly labelled {@link ActionSource#REFERENCE}.
 *
 * <p>The table is short and honest about being static rather than pretending to be a feed. A
 * production system would buy a corporate-actions vendor; that is stated in the README rather
 * than papered over.
 */
@Service
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final ReferenceDataService referenceData;
    private final MarketClock clock;
    private final CorporateActionRepository repository;
    private final Map<InstrumentId, List<RecordedAction>> byInstrument = new ConcurrentHashMap<>();

    public CorporateActionService(ReferenceDataService referenceData, MarketClock clock,
                                  CorporateActionRepository repository) {
        this.referenceData = referenceData;
        this.clock = clock;
        this.repository = repository;
    }

    /**
     * Loads persisted actions, then tops up from the reference table.
     *
     * <p>Load order matters. Persisted actions come first so that one already stored - including a
     * hand-declared correction to a reference entry - is present before seeding, and the seed's
     * de-duplication then leaves it alone rather than overwriting it.
     *
     * <p>In {@code @PostConstruct} rather than the constructor: a constructor that talks to a
     * database makes the bean impossible to build in a unit test without one.
     */
    @PostConstruct
    void load() {
        int loaded = 0;
        for (CorporateActionEntity entity : repository.findAll()) {
            try {
                RecordedAction recorded = entity.toDomain();
                addInMemory(recorded);
                loaded++;
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable persisted corporate action {}: {}",
                        entity.getId(), e.getMessage());
            }
        }
        if (loaded > 0) {
            log.info("Loaded {} persisted corporate action(s)", loaded);
        }
        seedKnownSplits();
    }

    /**
     * Real US equity splits, used because no working vendor feed supplies them.
     *
     * <p>Only actions for instruments actually in the registry are recorded, so configuring a
     * different watchlist does not leave orphaned entries behind.
     */
    private void seedKnownSplits() {
        record Known(String symbol, String exDate, int newShares, int oldShares) {
        }
        List<Known> splits = List.of(
                new Known("NVDA", "2024-06-10", 10, 1),
                new Known("NVDA", "2021-07-20", 4, 1),
                new Known("AAPL", "2020-08-31", 4, 1),
                new Known("TSLA", "2022-08-25", 3, 1),
                new Known("TSLA", "2020-08-31", 5, 1),
                new Known("AMZN", "2022-06-06", 20, 1),
                new Known("GOOGL", "2022-07-18", 20, 1));

        int recorded = 0;
        for (Known known : splits) {
            Optional<Instrument> instrument = referenceData.resolve(known.symbol());
            if (instrument.isEmpty()) {
                continue;
            }
            record(new CorporateAction.Split(
                            instrument.get().id(),
                            LocalDate.parse(known.exDate()),
                            BigDecimal.valueOf(known.newShares()),
                            BigDecimal.valueOf(known.oldShares())),
                    ActionSource.REFERENCE);
            recorded++;
        }
        log.info("Seeded {} known historical splits from the reference table", recorded);
    }

    /**
     * Records an action, ignoring one already known from any source.
     *
     * @return true if it was new
     */
    public boolean record(CorporateAction action, ActionSource source) {
        RecordedAction candidate = new RecordedAction(action, source, clock.now());
        if (!addInMemory(candidate)) {
            return false;
        }
        try {
            // The unique constraint on dedupe_key is the real guard. The in-memory check above
            // catches the common case; this catches two threads racing the same insert.
            if (!repository.existsByDedupeKey(candidate.dedupeKey())) {
                repository.save(CorporateActionEntity.from(candidate));
            }
        } catch (RuntimeException e) {
            // A failure to persist must not lose the action for this run - the adjustment is
            // still correct in memory, it just will not survive a restart.
            log.warn("Could not persist corporate action {}: {}",
                    candidate.dedupeKey(), e.getMessage());
        }
        return true;
    }

    /** Adds to the in-memory index, returning false if an identical action is already there. */
    private boolean addInMemory(RecordedAction candidate) {
        List<RecordedAction> existing = byInstrument.computeIfAbsent(
                candidate.action().instrumentId(), id -> new ArrayList<>());
        synchronized (existing) {
            for (RecordedAction recorded : existing) {
                if (recorded.dedupeKey().equals(candidate.dedupeKey())) {
                    return false;
                }
            }
            existing.add(candidate);
            existing.sort(Comparator.comparing(r -> r.action().exDate()));
            return true;
        }
    }

    /** Records a batch, returning how many were new. */
    public int recordAll(List<CorporateAction> actions, ActionSource source) {
        int added = 0;
        for (CorporateAction action : actions) {
            if (record(action, source)) {
                added++;
            }
        }
        return added;
    }

    /** Declares a forward or reverse split by hand. */
    public CorporateAction declareSplit(InstrumentId id, LocalDate exDate,
                                        BigDecimal newShares, BigDecimal oldShares) {
        CorporateAction.Split split = new CorporateAction.Split(id, exDate, newShares, oldShares);
        record(split, ActionSource.DECLARED);
        return split;
    }

    /** Declares a cash dividend by hand. */
    public CorporateAction declareDividend(InstrumentId id, LocalDate exDate, LocalDate payDate,
                                           Money amountPerShare) {
        CorporateAction.CashDividend dividend =
                new CorporateAction.CashDividend(id, exDate, payDate, amountPerShare);
        record(dividend, ActionSource.DECLARED);
        return dividend;
    }

    public List<RecordedAction> recordedFor(InstrumentId instrumentId) {
        List<RecordedAction> actions = byInstrument.get(instrumentId);
        if (actions == null) {
            return List.of();
        }
        synchronized (actions) {
            return List.copyOf(actions);
        }
    }

    public List<CorporateAction> actionsFor(InstrumentId instrumentId) {
        return recordedFor(instrumentId).stream().map(RecordedAction::action).toList();
    }

    /** Every recorded action, newest ex-date first, for the corporate-actions panel. */
    public List<RecordedAction> all() {
        List<RecordedAction> out = new ArrayList<>();
        byInstrument.values().forEach(list -> {
            synchronized (list) {
                out.addAll(list);
            }
        });
        out.sort(Comparator.comparing((RecordedAction r) -> r.action().exDate()).reversed());
        return out;
    }

    /** Actions with an ex-date in the given window, for "what is coming up" display. */
    public List<RecordedAction> between(LocalDate from, LocalDate to) {
        return all().stream()
                .filter(r -> !r.action().exDate().isBefore(from) && !r.action().exDate().isAfter(to))
                .sorted(Comparator.comparing(r -> r.action().exDate()))
                .toList();
    }

    public Map<InstrumentId, List<CorporateAction>> allByInstrument() {
        Map<InstrumentId, List<CorporateAction>> out = new LinkedHashMap<>();
        byInstrument.forEach((id, list) -> {
            synchronized (list) {
                out.put(id, list.stream().map(RecordedAction::action).toList());
            }
        });
        return out;
    }

    public int count() {
        return byInstrument.values().stream().mapToInt(List::size).sum();
    }
}
