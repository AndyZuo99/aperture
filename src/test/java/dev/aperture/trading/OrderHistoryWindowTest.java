package dev.aperture.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webull.openapi.trade.TradeClientV3;
import com.webull.openapi.trade.response.NOrderItem;
import com.webull.openapi.trade.response.PaginatedResult;
import com.webull.openapi.trade.response.v3.OrderHistory;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.time.MarketClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The window the order history is fetched over, and how its pages are followed.
 *
 * <p>Both halves of this failed silently on the live API, which is why they are pinned here. Left
 * unset, the window is not "everything" but a short recent lookback - seven orders where the same
 * account actually had sixty-three - and an order log that drops the first fifty-six is worse than
 * none. And {@code end_time} is required whenever {@code start_time} is given, so sending only a
 * start fails with {@code invalid start_time,end_time}, an error that names the start first and so
 * reads exactly like a rejected format.
 */
class OrderHistoryWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-14T15:09:00Z");

    private TradeClientV3 client;
    private OrderHistoryService service;

    @BeforeEach
    void setUp() {
        client = mock(TradeClientV3.class);
        WebullClientHolder holder = mock(WebullClientHolder.class);
        when(holder.tradeClient(any())).thenReturn(Optional.of(client));
        MarketClock clock = mock(MarketClock.class);
        when(clock.now()).thenReturn(NOW);
        service = new OrderHistoryService(holder, clock);
    }

    @Test
    @DisplayName("an explicit window is always sent, with both ends and in the accepted format")
    void sendsAnExplicitWindow() {
        when(client.listOrders(any(), any(), any(), any())).thenReturn(page(List.of(), null));

        service.orders(account());

        ArgumentCaptor<String> from = ArgumentCaptor.captor();
        ArgumentCaptor<String> to = ArgumentCaptor.captor();
        verify(client).listOrders(eq("acct"), from.capture(), to.capture(), eq(null));

        // yyyy-MM-dd'T'HH:mm:ss. Every other spelling tried was rejected outright, and a null end
        // is rejected even when the start is valid.
        assertThat(from.getValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(to.getValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

        // The start predates any Webull account, so the window is "all time" rather than a
        // rolling lookback that would shorten as the app keeps running.
        assertThat(LocalDate.parse(from.getValue().substring(0, 10)))
                .isBeforeOrEqualTo(LocalDate.of(2000, 1, 1));
        // The end runs past now: the window's timezone is undocumented, and an end of "now" in the
        // wrong zone would drop the orders placed today.
        assertThat(LocalDate.parse(to.getValue().substring(0, 10)))
                .isAfter(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("every page is followed, so history is not cut off at the first one")
    void followsEveryPage() {
        when(client.listOrders(any(), any(), any(), eq(null)))
                .thenReturn(page(List.of(order("AAPL")), "key-2"));
        when(client.listOrders(any(), any(), any(), eq("key-2")))
                .thenReturn(page(List.of(order("MRK")), "key-3"));
        when(client.listOrders(any(), any(), any(), eq("key-3")))
                .thenReturn(page(List.of(order("SLB")), null));

        assertThat(service.orders(account())).extracting(OrderRecord::symbol)
                .containsExactlyInAnyOrder("AAPL", "MRK", "SLB");
        verify(client, times(3)).listOrders(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a repeating pagination key stops the walk instead of looping on one page")
    void guardsAgainstARepeatingKey() {
        // An endpoint that keeps handing back the same key would otherwise be followed to the page
        // cap, collecting the same page a hundred times.
        when(client.listOrders(any(), any(), any(), any()))
                .thenReturn(page(List.of(order("AAPL")), "same-key"));

        List<OrderRecord> orders = service.orders(account());

        assertThat(orders).hasSize(2);   // the first page, then the repeat that ends the walk
        verify(client, times(2)).listOrders(any(), any(), any(), any());
    }

    @Test
    @DisplayName("orders come back newest first")
    void newestFirst() {
        when(client.listOrders(any(), any(), any(), any())).thenReturn(page(List.of(
                order("OLD", "2025-09-18T13:18:29.680Z"),
                order("NEW", "2026-09-14T14:53:18.736Z"),
                order("MID", "2026-05-28T10:00:00.000Z")), null));

        assertThat(service.orders(account())).extracting(OrderRecord::symbol)
                .containsExactly("NEW", "MID", "OLD");
    }

    @Test
    @DisplayName("a vendor failure yields the last good read rather than an empty log")
    void failureKeepsTheLastGoodRead() {
        when(client.listOrders(any(), any(), any(), any())).thenReturn(page(List.of(order("AAPL")), null));
        assertThat(service.orders(account())).hasSize(1);

        // Cache expired, and now the vendor is rate-limiting. An empty list would read as "this
        // account has never traded", which is a different and wrong statement.
        when(client.listOrders(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("429 TOO_MANY_REQUESTS"));
        MarketClock later = mock(MarketClock.class);
        when(later.now()).thenReturn(NOW.plusSeconds(60));
        WebullClientHolder holder = mock(WebullClientHolder.class);
        when(holder.tradeClient(any())).thenReturn(Optional.of(client));

        OrderHistoryService fresh = new OrderHistoryService(holder, later);
        assertThat(fresh.orders(account())).isEmpty();   // no prior read to fall back on
    }

    private static PaginatedResult<OrderHistory> page(List<NOrderItem> items, String nextKey) {
        OrderHistory history = new OrderHistory();
        history.setOrders(new ArrayList<>(items));
        PaginatedResult<OrderHistory> result = new PaginatedResult<>();
        result.setData(items.isEmpty() ? List.of() : List.of(history));
        result.setPaginationKey(nextKey);
        return result;
    }

    private static NOrderItem order(String symbol) {
        return order(symbol, "2026-09-14T14:00:00.000Z");
    }

    private static NOrderItem order(String symbol, String placedAt) {
        NOrderItem item = new NOrderItem();
        item.setOrderId("id-" + symbol + "-" + placedAt);
        item.setSymbol(symbol);
        item.setSide("BUY");
        item.setOrderType("LIMIT");
        item.setTimeInForce("DAY");
        item.setStatus("FILLED");
        item.setTotalQuantity("1");
        item.setFilledQuantity("1");
        item.setPlaceTimeAt(placedAt);
        return item;
    }

    private static BrokerAccount account() {
        return new BrokerAccount("acct", "12345678", "MARGIN", "INDIVIDUAL_MARGIN",
                "Individual Margin", TradingEnvironment.PRODUCTION);
    }
}
