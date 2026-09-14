package dev.aperture.trading;

import com.webull.openapi.trade.TradeClientV3;
import com.webull.openapi.trade.response.NOrderItem;
import com.webull.openapi.trade.response.OrderCommissionItem;
import com.webull.openapi.trade.response.OrderFeeItem;
import com.webull.openapi.trade.response.PaginatedResult;
import com.webull.openapi.trade.response.v3.OrderHistory;
import dev.aperture.account.BrokerAccount;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Order history for an account, in either environment.
 *
 * <h2>Why the vendor call looks the way it does</h2>
 *
 * <p>{@code listOrders} has two forms. The five-argument one is deprecated. The four-argument form
 * is {@code (accountId, startTime, endTime, paginationKey)} - the second argument is
 * <em>start_time</em>, not a page size, which is worth stating because it looks exactly like one
 * and passing {@code 20} fails with {@code invalid start_time, value: 20}.
 *
 * <p>No date format was accepted for {@code start_time} on the live API - {@code 2026-09-01},
 * {@code 2026-09-01T00:00:00Z}, {@code 20260901} and epoch milliseconds were all rejected as
 * invalid - so the window is left unset, which the vendor accepts and which returns the account's
 * orders. Pagination is followed through {@code paginationKey} until it comes back empty.
 *
 * <h2>Rate limits are real here</h2>
 *
 * <p>Iterating five accounts back to back returned {@code Too many requests}. Each account is
 * therefore cached briefly and fetched only when its own panel asks, rather than the whole set
 * being warmed on a schedule.
 */
@Service
public class OrderHistoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);

    /** Short enough that a submitted order shows up promptly, long enough to absorb a reload. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);

    /** Pages to follow before stopping. A guard against an endpoint that never stops paging. */
    private static final int MAX_PAGES = 10;

    private final WebullClientHolder holder;
    private final MarketClock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OrderHistoryService(WebullClientHolder holder, MarketClock clock) {
        this.holder = holder;
        this.clock = clock;
    }

    /** Orders for one account, newest first. Empty when the environment is not connected. */
    public List<OrderRecord> orders(BrokerAccount account) {
        Cached cached = cache.get(account.key());
        if (cached != null && cached.fetchedAt.plus(CACHE_TTL).isAfter(clock.now())) {
            return cached.orders;
        }
        Optional<TradeClientV3> client = holder.tradeClient(account.environment());
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            List<OrderRecord> orders = fetchAllPages(client.get(), account.accountId());
            // Newest first: the order a trader wants is the one they just placed.
            orders.sort(Comparator.comparing(OrderRecord::placedAt).reversed());
            cache.put(account.key(), new Cached(orders, clock.now()));
            return orders;
        } catch (RuntimeException e) {
            log.warn("Could not read orders for {} ({}): {}",
                    account.maskedNumber(), account.environment().label(), e.getMessage());
            // A stale list beats an empty one: the panel keeps showing the last good read rather
            // than implying the account has never traded.
            return cached == null ? List.of() : cached.orders;
        }
    }

    private List<OrderRecord> fetchAllPages(TradeClientV3 client, String accountId) {
        List<OrderRecord> orders = new ArrayList<>();
        String paginationKey = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            PaginatedResult<OrderHistory> result =
                    client.listOrders(accountId, null, null, paginationKey);
            if (result == null || result.getData() == null || result.getData().isEmpty()) {
                break;
            }
            for (OrderHistory history : result.getData()) {
                if (history.getOrders() == null) {
                    continue;
                }
                // A combo order carries its legs in one entry; each leg is its own order at the
                // venue, with its own status and its own fill.
                for (NOrderItem item : history.getOrders()) {
                    toRecord(item).ifPresent(orders::add);
                }
            }
            paginationKey = result.getPaginationKey();
            if (paginationKey == null || paginationKey.isBlank()) {
                break;
            }
        }
        return orders;
    }

    private Optional<OrderRecord> toRecord(NOrderItem item) {
        if (item == null || item.getOrderId() == null || item.getSymbol() == null) {
            return Optional.empty();
        }
        Instant placedAt = instant(item.getPlaceTimeAt(), item.getPlaceTime());
        if (placedAt == null) {
            // Without a placement time there is no ordering and no fill duration; a row that
            // cannot be placed in time is worse than one that is missing.
            return Optional.empty();
        }
        return Optional.of(new OrderRecord(
                item.getOrderId(),
                item.getClientOrderId() == null ? "" : item.getClientOrderId(),
                item.getSymbol(),
                item.getInstrumentType() == null ? "" : item.getInstrumentType(),
                Optional.ofNullable(item.getEventOutcome())
                        .filter(outcome -> !outcome.isBlank())
                        .map(outcome -> outcome.toUpperCase(java.util.Locale.ROOT)),
                item.getSide() == null ? "" : item.getSide(),
                item.getOrderType() == null ? "" : item.getOrderType(),
                item.getTimeInForce() == null ? "" : item.getTimeInForce(),
                OrderStatus.parse(item.getStatus()),
                item.getStatus() == null ? "" : item.getStatus(),
                // totalQuantity, not quantity: the latter is deprecated and null on every order
                // the live API has returned.
                quantity(item.getTotalQuantity()),
                quantity(item.getFilledQuantity()),
                price(item.getLimitPrice()),
                price(item.getStopPrice()),
                price(item.getFilledPrice()),
                placedAt,
                Optional.ofNullable(instant(item.getFilledTimeAt(), item.getFilledTime())),
                commission(item.getCommission()),
                fees(item.getFees())));
    }

    /**
     * Prefers the ISO instant; falls back to the epoch-millis field when it is absent.
     *
     * <p>The vendor sends both, and the millis field is a <em>string</em> - the pair arrives as
     * {@code 1789312511570} and {@code 2026-09-13T15:15:11.570Z} for the same moment.
     */
    private static Instant instant(String isoTime, String epochMillis) {
        if (isoTime != null && !isoTime.isBlank()) {
            try {
                return Instant.parse(isoTime);
            } catch (DateTimeParseException e) {
                log.debug("Unparseable order timestamp: {}", isoTime);
            }
        }
        BigDecimal millis = decimal(epochMillis);
        return millis == null || millis.signum() <= 0 ? null : Instant.ofEpochMilli(millis.longValue());
    }

    private static Quantity quantity(String value) {
        BigDecimal parsed = decimal(value);
        return parsed == null ? Quantity.zero() : Quantity.of(parsed);
    }

    private static Optional<Price> price(String value) {
        BigDecimal parsed = decimal(value);
        return parsed == null || parsed.signum() <= 0 ? Optional.empty() : Optional.of(Price.of(parsed));
    }

    private static Optional<Money> commission(OrderCommissionItem item) {
        if (item == null) {
            return Optional.empty();
        }
        // Actual first; the receivable figure is what is expected to be charged, not what was.
        BigDecimal actual = decimal(item.getActualCommission());
        BigDecimal amount = actual != null ? actual : decimal(item.getReceivableCommission());
        return amount == null ? Optional.empty() : Optional.of(Money.usd(amount));
    }

    private static Optional<Money> fees(List<OrderFeeItem> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        Money total = Money.zero();
        boolean any = false;
        for (OrderFeeItem item : items) {
            BigDecimal actual = decimal(item.getActualValue());
            BigDecimal amount = actual != null ? actual : decimal(item.getReceivableValue());
            if (amount != null) {
                total = total.plus(Money.usd(amount));
                any = true;
            }
        }
        return any ? Optional.of(total) : Optional.empty();
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Cached(List<OrderRecord> orders, Instant fetchedAt) {
    }
}
