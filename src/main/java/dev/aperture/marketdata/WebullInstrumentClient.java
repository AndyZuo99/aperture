package dev.aperture.marketdata;

import com.webull.openapi.core.common.dict.Category;
import com.webull.openapi.data.DataClient;
import com.webull.openapi.data.quotes.domain.CryptoInstrumentDetail;
import com.webull.openapi.data.quotes.domain.EventInstrumentParam;
import com.webull.openapi.data.quotes.domain.EventMarket;
import com.webull.openapi.data.quotes.domain.EventSeries;
import com.webull.openapi.data.quotes.domain.FuturesProduct;
import com.webull.openapi.data.quotes.domain.InstrumentQueryParam;
import com.webull.openapi.data.quotes.domain.StockInstrumentDetail;
import dev.aperture.instrument.TradableInstrument;
import dev.aperture.instrument.TradableUniverse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches the tradable universe for each asset class.
 *
 * <p>Separate from {@link WebullQuoteClient} because these are reference-data calls with entirely
 * different shapes, cadences and failure modes: quotes are polled every few seconds and these
 * lists change once a day at most.
 *
 * <p><strong>Vendor status codes.</strong> {@code OC} means open for trading; {@code NT} appears
 * on delisted and not-yet-trading instruments alike. Both are returned - a universe listing that
 * silently hid everything untradable would make a missing symbol look like a bug - but only
 * {@code OC} is flagged {@code tradable}.
 */
@Component
public class WebullInstrumentClient {

    private static final Logger log = LoggerFactory.getLogger(WebullInstrumentClient.class);

    /** The vendor's "open, currently trading" status. */
    private static final String STATUS_OPEN = "OC";

    /**
     * Pause between the per-series event calls.
     *
     * <p>The API returns {@code 429 TOO_MANY_REQUESTS} after only a handful of rapid requests, and
     * the event universe needs one call per series. Without this the listing half-loads and the
     * failures look like missing markets.
     */
    private static final long EVENT_CALL_SPACING_MS = 350;

    /**
     * Cap on equity pages fetched.
     *
     * <p>A thousand instruments per page, so this is up to 20,000 - comfortably more than the US
     * listed universe including preferreds, rights and units. The cap exists so that a vendor
     * that keeps handing back pagination keys cannot spin this into an unbounded fetch loop; if
     * it is ever reached the listing is a prefix of the universe rather than all of it.
     */
    private static final int MAX_EQUITY_PAGES = 20;

    private final WebullClientHolder holder;

    public WebullInstrumentClient(WebullClientHolder holder) {
        this.holder = holder;
    }

    /**
     * Daily bars for one instrument in any universe.
     *
     * <p>Each asset class has its own bar endpoint - {@code getBatchBars} for equities,
     * {@code getCryptoBars}, {@code getEventBars} - and they are not interchangeable.
     *
     * <p>Futures are absent on purpose: {@code getFuturesBars} returns
     * {@code 403 MARKET_DATA_NOT_SUBSCRIBED} on a Nasdaq Basic entitlement, because futures data
     * is a separate product. Futures contracts can still be listed from reference data; they
     * simply cannot be analysed, and an empty series here is what makes the recommender decline
     * to propose them rather than inventing a trend.
     */
    public List<dev.aperture.marketdata.Bar> history(String symbol, TradableUniverse universe,
                                                     int count) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty() || symbol == null || symbol.isBlank()) {
            return List.of();
        }
        dev.aperture.instrument.InstrumentId id =
                dev.aperture.instrument.InstrumentId.of(symbol.toUpperCase());
        try {
            return switch (universe) {
                case EQUITY -> equityHistory(client.get(), symbol, id, count);
                case CRYPTO -> barsFrom(client.get().getCryptoBars(
                        java.util.Set.of(symbol.toUpperCase()),
                        Category.US_CRYPTO.name(), "D", count, Boolean.FALSE), id);
                case EVENT -> eventHistory(client.get(), symbol, id, count);
                case FUTURES -> List.of();
            };
        } catch (RuntimeException e) {
            log.debug("History for {} ({}) unavailable: {}", symbol, universe, e.getMessage());
            return List.of();
        }
    }

    private List<dev.aperture.marketdata.Bar> equityHistory(
            DataClient client, String symbol,
            dev.aperture.instrument.InstrumentId id, int count) {
        var response = client.getBatchBars(List.of(symbol.toUpperCase()),
                Category.US_STOCK.name(), "D", count);
        if (response == null || response.getResult() == null || response.getResult().isEmpty()) {
            return List.of();
        }
        return toBars(response.getResult().get(0).getResult(), id,
                dev.aperture.corporate.PriceBasis.SPLIT_ADJUSTED);
    }

    private List<dev.aperture.marketdata.Bar> eventHistory(
            DataClient client, String symbol,
            dev.aperture.instrument.InstrumentId id, int count) {
        var response = client.getEventBars(java.util.Set.of(symbol.toUpperCase()),
                Category.US_EVENT.name(), "D", count, Boolean.FALSE);
        if (response == null || response.isEmpty()) {
            return List.of();
        }
        // Event bars come back as Kdata rather than the shared Bar type, so they are mapped
        // separately. A binary contract never splits, so the series is RAW by definition.
        List<dev.aperture.marketdata.Bar> bars = new ArrayList<>();
        var kdata = response.get(0).getResult();
        if (kdata == null) {
            return List.of();
        }
        for (var k : kdata) {
            toBar(id, k.getTime(), k.getOpen(), k.getHigh(), k.getLow(), k.getClose(),
                    k.getVolume(), dev.aperture.corporate.PriceBasis.RAW).ifPresent(bars::add);
        }
        bars.sort(java.util.Comparator.comparing(dev.aperture.marketdata.Bar::sessionDate));
        return bars;
    }

    private List<dev.aperture.marketdata.Bar> barsFrom(
            List<com.webull.openapi.data.quotes.domain.NBar> response,
            dev.aperture.instrument.InstrumentId id) {
        if (response == null || response.isEmpty()) {
            return List.of();
        }
        return toBars(response.get(0).getResult(), id, dev.aperture.corporate.PriceBasis.RAW);
    }

    private List<dev.aperture.marketdata.Bar> toBars(
            List<com.webull.openapi.data.quotes.domain.Bar> vendorBars,
            dev.aperture.instrument.InstrumentId id,
            dev.aperture.corporate.PriceBasis basis) {
        if (vendorBars == null) {
            return List.of();
        }
        List<dev.aperture.marketdata.Bar> bars = new ArrayList<>(vendorBars.size());
        for (var vendor : vendorBars) {
            toBar(id, vendor.getTime(), vendor.getOpen(), vendor.getHigh(), vendor.getLow(),
                    vendor.getClose(), vendor.getVolume(), basis).ifPresent(bars::add);
        }
        bars.sort(java.util.Comparator.comparing(dev.aperture.marketdata.Bar::sessionDate));
        return bars;
    }

    private Optional<dev.aperture.marketdata.Bar> toBar(
            dev.aperture.instrument.InstrumentId id, String time, String open, String high,
            String low, String close, String volume,
            dev.aperture.corporate.PriceBasis basis) {
        java.math.BigDecimal o = WebullQuoteClient.decimal(open);
        java.math.BigDecimal h = WebullQuoteClient.decimal(high);
        java.math.BigDecimal l = WebullQuoteClient.decimal(low);
        java.math.BigDecimal c = WebullQuoteClient.decimal(close);
        if (o == null || h == null || l == null || c == null || time == null) {
            return Optional.empty();
        }
        try {
            java.time.Instant start = java.time.OffsetDateTime
                    .parse(time, WebullQuoteClient.VENDOR_TIMESTAMP).toInstant();
            return Optional.of(new dev.aperture.marketdata.Bar(
                    id,
                    start.atZone(dev.aperture.time.MarketCalendar.EXCHANGE_ZONE).toLocalDate(),
                    dev.aperture.common.Price.of(o), dev.aperture.common.Price.of(h),
                    dev.aperture.common.Price.of(l), dev.aperture.common.Price.of(c),
                    dev.aperture.common.Quantity.of(
                            WebullQuoteClient.decimal(volume) == null
                                    ? java.math.BigDecimal.ZERO
                                    : WebullQuoteClient.decimal(volume)),
                    basis));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Fetches one universe. Returns empty rather than throwing when the vendor refuses. */
    public List<TradableInstrument> fetch(TradableUniverse universe) {
        Optional<DataClient> client = holder.dataClient();
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            return switch (universe) {
                case EQUITY -> equities(client.get());
                case FUTURES -> futures(client.get());
                case CRYPTO -> crypto(client.get());
                case EVENT -> events(client.get());
            };
        } catch (RuntimeException e) {
            log.warn("Could not load the {} universe: {}", universe.label(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Listed equities and ETFs.
     *
     * <p>The margin and borrow attributes are the interesting part here: they are what actually
     * differs between what a cash account and a margin account can do with the same security.
     */
    private List<TradableInstrument> equities(DataClient client) {
        // Paginated. One page is a thousand instruments ordered by vendor instrument id, and the
        // first page happens to be entirely ETFs - so a single call produces an equity universe
        // containing no actual equities. AAPL is several pages in.
        List<StockInstrumentDetail> rows = new ArrayList<>();
        String paginationKey = null;
        for (int page = 0; page < MAX_EQUITY_PAGES; page++) {
            InstrumentQueryParam query = new InstrumentQueryParam();
            query.setCategory(Category.US_STOCK.name());
            if (paginationKey != null) {
                query.setPaginationKey(paginationKey);
            }
            var response = client.getInstrumentsV2(query);
            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                break;
            }
            rows.addAll(response.getData());
            paginationKey = response.getPaginationKey();
            if (paginationKey == null || paginationKey.isBlank()) {
                break;
            }
            try {
                Thread.sleep(EVENT_CALL_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<TradableInstrument> out = new ArrayList<>(rows.size());
        for (StockInstrumentDetail row : rows) {
            var attributes = new TradableInstrument.Attributes()
                    .put("Exchange", row.getExchangeCode())
                    .putFlag("Marginable", row.getMarginable())
                    .putFlag("Shortable", row.getShortable())
                    .putFlag("Easy to borrow", row.getEasyToBorrow())
                    .putFlag("Fractional", row.getFractionable())
                    .putFlag("Overnight", row.getOvernightTradingSupported())
                    .putPercent("Margin req. long", row.getMarginRequirementLong())
                    .putPercent("Margin req. short", row.getMarginRequirementShort())
                    .putDecimal("Lot size", row.getLotSize())
                    .build();
            out.add(new TradableInstrument(
                    row.getSymbol(), row.getName(), TradableUniverse.EQUITY,
                    subCategoryOf(row), row.getStatus(),
                    STATUS_OPEN.equals(row.getStatus()), attributes));
        }
        return out;
    }

    private static String subCategoryOf(StockInstrumentDetail row) {
        String subCategory = row.getSubCategory();
        return subCategory == null || subCategory.isBlank() ? "Stock" : subCategory;
    }

    /**
     * Futures contracts, grouped by product class (Energy, Equities, Interest Rate, ...).
     *
     * <p>{@code getFuturesProducts} already returns individual dated contracts rather than
     * abstract products - the codes look like {@code ESZ26-ES} - so this is the contract list, not
     * a product list that needs expanding. Names come back null for many of them, which
     * {@link TradableInstrument} handles by falling back to the code.
     */
    private List<TradableInstrument> futures(DataClient client) {
        List<FuturesProduct> products = client.getFuturesProducts(Category.US_FUTURES.name());
        if (products == null) {
            return List.of();
        }
        List<TradableInstrument> out = new ArrayList<>(products.size());
        for (FuturesProduct product : products) {
            var attributes = new TradableInstrument.Attributes()
                    .put("Exchange", product.getExchangeCode())
                    .put("Product class", product.getProductClassName())
                    .build();
            out.add(new TradableInstrument(
                    product.getCode(), product.getName(), TradableUniverse.FUTURES,
                    product.getProductClassName() == null ? "Other" : product.getProductClassName(),
                    "", true, attributes));
        }
        return out;
    }

    /** Spot crypto pairs. The vendor returns no display name, so the symbol stands in. */
    private List<TradableInstrument> crypto(DataClient client) {
        InstrumentQueryParam query = new InstrumentQueryParam();
        query.setCategory(Category.US_CRYPTO.name());
        List<CryptoInstrumentDetail> rows = client.getCryptoInstrument(query);
        if (rows == null) {
            return List.of();
        }
        List<TradableInstrument> out = new ArrayList<>(rows.size());
        for (CryptoInstrumentDetail row : rows) {
            var attributes = new TradableInstrument.Attributes()
                    .putDecimal("Min qty", row.getMinTradeQty())
                    .putDecimal("Min notional", row.getMinTradeAmt())
                    .putDecimal("Max notional", row.getMaxTradeAmt())
                    .putDecimal("Price step", row.getPriceStep())
                    .putDecimal("Lot size", row.getLotSize())
                    .build();
            out.add(new TradableInstrument(
                    row.getSymbol(), row.getName(), TradableUniverse.CRYPTO,
                    "Spot", row.getStatus(),
                    STATUS_OPEN.equals(row.getStatus()), attributes));
        }
        return out;
    }

    /**
     * Binary event contracts, gathered series by series.
     *
     * <p>There is no "list every event contract" endpoint: the vendor models them as series (Fed
     * Meeting, Jobs numbers) each containing dated markets, and
     * {@code getEventInstrumentsList} rejects a request with no series symbol
     * ({@code series_symbol is blank}). So this walks the series and collects each one's markets,
     * paced to stay under the rate limit.
     */
    private List<TradableInstrument> events(DataClient client) {
        List<EventSeries> seriesList = client.getEventSeriesList(null, null, null, 100);
        if (seriesList == null || seriesList.isEmpty()) {
            return List.of();
        }
        List<TradableInstrument> out = new ArrayList<>();
        for (EventSeries series : seriesList) {
            if (series.getSymbol() == null || series.getSymbol().isBlank()) {
                continue;
            }
            try {
                EventInstrumentParam param = new EventInstrumentParam();
                param.setSeriesSymbol(series.getSymbol());
                param.setPageSize(100);
                List<EventMarket> markets = client.getEventInstrumentsList(param);
                if (markets == null) {
                    continue;
                }
                for (EventMarket market : markets) {
                    var attributes = new TradableInstrument.Attributes()
                            .put("Series", series.getName())
                            .put("Category", series.getCategory())
                            .put("Frequency", series.getFrequency())
                            .put("Settles on", market.getLastTradingDate())
                            .put("Payout", market.getPayoutDate())
                            .putFlag("Close early", market.getCanCloseEarly())
                            .build();
                    out.add(new TradableInstrument(
                            market.getSymbol(),
                            // The market name IS the question being settled - "Will the Federal
                            // Reserve hike rates by ..." - so it is the most useful label.
                            market.getName(),
                            TradableUniverse.EVENT,
                            series.getName() == null ? series.getSymbol() : series.getName(),
                            market.getStatus(),
                            STATUS_OPEN.equals(market.getTradableStatus()),
                            attributes));
                }
                Thread.sleep(EVENT_CALL_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.debug("Event series {} unavailable: {}", series.getSymbol(), e.getMessage());
            }
        }
        return out;
    }
}
