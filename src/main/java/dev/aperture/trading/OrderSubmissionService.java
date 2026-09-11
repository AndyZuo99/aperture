package dev.aperture.trading;

import com.webull.openapi.core.common.dict.InstrumentSuperType;
import com.webull.openapi.core.common.dict.Markets;
import com.webull.openapi.core.common.dict.OrderSide;
import com.webull.openapi.core.common.dict.OrderTIF;
import com.webull.openapi.core.common.dict.OrderType;
import com.webull.openapi.trade.TradeClientV3;
import com.webull.openapi.trade.request.v3.TradeOrder;
import com.webull.openapi.trade.request.v3.TradeOrderItem;
import com.webull.openapi.trade.response.NOrderItem;
import com.webull.openapi.trade.response.v3.OrderHistory;
import com.webull.openapi.trade.response.v3.TradeOrderResponse;
import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.ai.OrderLeg;
import dev.aperture.ai.TradeRecommendation;
import dev.aperture.config.ApertureProperties;
import dev.aperture.instrument.TradableUniverse;
import dev.aperture.marketdata.WebullClientHolder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Submits a recommendation's two legs to the broker.
 *
 * <h2>The AI cannot reach this</h2>
 *
 * <p>Deliberately in its own package, and deliberately absent from {@code AnalystToolkit}. The
 * model proposes; a human submits. {@code AnalystToolkitIsReadOnlyTest} still fails the build if a
 * mutating method appears on the toolkit, so the separation is enforced rather than merely
 * intended.
 *
 * <h2>The legs are sequenced, not fired together</h2>
 *
 * <p>The exit is a sell for shares the account does not own until the entry fills. Sending both at
 * once risks the venue rejecting the sell outright, or worse accepting it as a short. So the entry
 * goes first, its fill is polled, and the exit is sized to <em>what actually filled</em> - a
 * partial fill must not leave a sell order for more stock than is held.
 *
 * <h2>Production stays gated</h2>
 *
 * <p>Sandbox submits freely; it is paper. Production requires both conditions on
 * {@code ApertureProperties.Webull} - a boolean and a confirmation phrase - and is refused
 * otherwise, before anything reaches the venue.
 */
@Service
public class OrderSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(OrderSubmissionService.class);

    /** How long to wait for a market entry to fill before giving up on placing the exit. */
    private static final int FILL_POLL_ATTEMPTS = 10;
    private static final long FILL_POLL_INTERVAL_MS = 600;

    /**
     * Regular trading hours only. The only session a MARKET order may carry.
     *
     * <p>Outside 09:30-16:00 the venue refuses it outright: "Only limit orders are supported for
     * extended-hours trading."
     */
    private static final String SESSION_REGULAR_ONLY = "N";

    /**
     * Regular plus pre- and post-market. Valid on LIMIT orders only - sending it with a market
     * order is rejected as {@code invalid support_trading_session, value: ALL}.
     */
    private static final String SESSION_INCLUDING_EXTENDED = "ALL";

    /**
     * Premium over the ask when converting the entry to a marketable limit for extended hours.
     *
     * <p>Twenty basis points: enough to cross a widened after-hours spread and actually trade,
     * small enough to bound the damage. A market order cannot be used here at all, and an
     * unpriced "just get me in" outside regular hours is exactly how people get filled badly.
     */
    private static final BigDecimal MARKETABLE_LIMIT_PREMIUM = new BigDecimal("1.002");

    private final WebullClientHolder holder;
    private final AccountService accounts;
    private final dev.aperture.time.MarketClock clock;
    private final ApertureProperties.Webull config;

    public OrderSubmissionService(WebullClientHolder holder, AccountService accounts,
                                  dev.aperture.time.MarketClock clock,
                                  ApertureProperties properties) {
        this.holder = holder;
        this.accounts = accounts;
        this.clock = clock;
        this.config = properties.webull();
    }

    /**
     * Submits the entry, waits for it, then rests the exit.
     *
     * @param recommendation the plan as priced; its legs are submitted verbatim
     */
    public SubmissionResult submit(TradingEnvironment environment, String accountId,
                                   TradeRecommendation recommendation) {
        return submit(environment, accountId, recommendation, false);
    }

    /**
     * @param allowExtendedHours whether to trade outside regular hours. A market order simply
     *     cannot: the venue accepts only limit orders in extended sessions. So when this is set
     *     and the market is shut, the entry is converted to a <em>marketable limit</em> a little
     *     above the ask - which is both what the venue permits and the safer instrument, since an
     *     unpriced order into a thin after-hours book is how people get filled badly. Left false,
     *     submission is refused outside regular hours and says why. The resting limit exit always
     *     carries the extended session, because a limit cannot fill worse than its price.
     */
    public SubmissionResult submit(TradingEnvironment environment, String accountId,
                                   TradeRecommendation recommendation,
                                   boolean allowExtendedHours) {
        Optional<BrokerAccount> account = accounts.findAccount(environment, accountId);
        if (account.isEmpty()) {
            return SubmissionResult.refused(
                    "No such account in " + environment.label() + ".",
                    environment.name(), "");
        }
        String label = account.get().displayName();

        // The gate, checked before anything is built or sent.
        if (environment.isReal() && !config.liveOrdersPermitted()) {
            return SubmissionResult.refused(
                    "Live order submission is disabled: " + config.liveOrderBlockReason()
                            + ". Sandbox submission is unaffected.",
                    environment.name(), label);
        }
        // Validate the plan itself before acquiring a connection: the cheapest and most decisive
        // checks first, so a bad request never gets as far as touching the venue's client.
        if (recommendation.legs().size() != 2) {
            return SubmissionResult.refused(
                    "Expected an entry and an exit leg.", environment.name(), label);
        }
        if (!recommendation.economics().viable()) {
            // The planner already decided this plan cannot profit. Submitting it anyway would
            // make the viability check decorative.
            return SubmissionResult.refused(
                    "This plan was marked unviable and will not be submitted: "
                            + String.join(" ", recommendation.economics().warnings()),
                    environment.name(), label);
        }
        Optional<TradeClientV3> client = holder.tradeClient(environment);
        if (client.isEmpty()) {
            return SubmissionResult.refused(
                    "No trading connection to " + environment.label() + ": "
                            + holder.unavailableReason(environment).orElse("not connected"),
                    environment.name(), label);
        }

        List<String> notes = new ArrayList<>();
        OrderLeg entryLeg = recommendation.legs().get(0);
        OrderLeg exitLeg = recommendation.legs().get(1);

        // A market order is a regular-hours instrument. Decided here rather than discovered from
        // a rejection, so the operator gets a reason instead of a vendor error code.
        boolean regularHours = clock.isRegularHours();
        String entrySession = SESSION_REGULAR_ONLY;

        if (entryLeg.type() == OrderLeg.Type.MARKET && !regularHours) {
            if (!allowExtendedHours) {
                return SubmissionResult.refused(
                        "The market is closed (" + clock.currentSession().label()
                                + "), and a market order can only be sent during regular hours. "
                                + "Submit between 09:30 and 16:00 ET, or allow extended hours to "
                                + "send a marketable limit instead.",
                        environment.name(), label);
            }
            BigDecimal limit = recommendation.economics().entryPrice().value()
                    .multiply(MARKETABLE_LIMIT_PREMIUM)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            entryLeg = new OrderLeg(entryLeg.side(), OrderLeg.Type.LIMIT, entryLeg.quantity(),
                    Optional.of(dev.aperture.common.Price.of(limit)), entryLeg.timeInForce(),
                    entryLeg.purpose());
            entrySession = SESSION_INCLUDING_EXTENDED;
            notes.add("The market is closed, so the entry was sent as a marketable limit at "
                    + limit.toPlainString() + " rather than a market order - extended-hours "
                    + "trading accepts limit orders only. It will not fill above that price.");
        }

        SubmissionResult.LegResult entry = send(client.get(), account.get(), recommendation,
                entryLeg, entrySession);
        if (entry.error().isPresent()) {
            notes.add("The entry was rejected, so no exit order was placed.");
            return new SubmissionResult(false, "", environment.name(), label,
                    Optional.of(entry), Optional.empty(), notes);
        }

        // Wait for the entry before resting a sell for stock that is not yet held.
        SubmissionResult.LegResult settled = awaitFill(client.get(), account.get(), entry);
        BigDecimal owned = settled.filledQuantity().orElse(BigDecimal.ZERO);

        if (owned.signum() <= 0) {
            notes.add("The entry has not filled yet (status " + settled.status() + "), so the "
                    + "good-til-cancelled exit was not placed. Place it once the entry fills, or "
                    + "the position will be left without a resting exit.");
            return new SubmissionResult(true, "", environment.name(), label,
                    Optional.of(settled), Optional.empty(), notes);
        }
        if (settled.isPartiallyFilled()) {
            notes.add("The entry filled partially. The exit is sized to the "
                    + owned.stripTrailingZeros().toPlainString()
                    + " actually filled, not the quantity requested.");
        }

        // Size the exit to what was actually filled, never to what was asked for.
        OrderLeg sized = new OrderLeg(exitLeg.side(), exitLeg.type(),
                dev.aperture.common.Quantity.of(owned), exitLeg.limitPrice(),
                exitLeg.timeInForce(), exitLeg.purpose());

        // The exit is a limit order, so extended-hours execution can only happen at the target.
        SubmissionResult.LegResult exit = send(client.get(), account.get(), recommendation, sized,
                SESSION_INCLUDING_EXTENDED);
        if (exit.error().isPresent()) {
            notes.add("The entry filled but the exit was rejected - this position currently has "
                    + "no resting exit.");
        }
        return new SubmissionResult(true, "", environment.name(), label,
                Optional.of(settled), Optional.of(exit), notes);
    }

    private SubmissionResult.LegResult send(TradeClientV3 client, BrokerAccount account,
                                            TradeRecommendation recommendation, OrderLeg leg,
                                            String tradingSession) {
        // Unique per attempt. Webull identifies, amends and cancels orders by the CLIENT id, so a
        // reused one is not an idempotency key but a collision.
        String clientOrderId = UUID.randomUUID().toString().replace("-", "");

        TradeOrderItem item = new TradeOrderItem();
        item.setClientOrderId(clientOrderId);
        // Required, and rejected as "invalid combo_type" when omitted. NORMAL is a standalone
        // order. The vendor also supports OTO/OCO/OTOCO brackets, which would let the venue
        // trigger the exit off the entry's fill instead of Aperture polling for it - see the
        // README; that is the better long-term shape for this pair.
        item.setComboType(com.webull.openapi.core.common.dict.ComboType.NORMAL.name());
        item.setSymbol(recommendation.symbol());
        item.setInstrumentType(instrumentTypeFor(recommendation.universe()));
        item.setMarket(Markets.US.name());
        item.setSide(leg.side() == OrderLeg.Side.BUY ? OrderSide.BUY.name() : OrderSide.SELL.name());
        item.setOrderType(leg.type() == OrderLeg.Type.MARKET
                ? OrderType.MARKET.name() : OrderType.LIMIT.name());
        item.setTimeInForce(leg.timeInForce() == OrderLeg.TimeInForce.GTC
                ? OrderTIF.GTC.name() : OrderTIF.DAY.name());
        item.setQuantity(leg.quantity().toDisplay().toPlainString());
        item.setEntrustType("QTY");
        item.setSupportTradingSession(tradingSession);
        leg.limitPrice().ifPresent(price ->
                item.setLimitPrice(price.toDisplay().toPlainString()));

        TradeOrder order = new TradeOrder();
        order.setClientOrderId(clientOrderId);
        order.setNewOrders(List.of(item));

        try {
            TradeOrderResponse response = client.placeOrder(account.accountId(), order);
            log.info("Submitted {} {} {} for {} to {} (clientOrderId {})",
                    leg.side(), leg.quantity().toDisplay().toPlainString(), leg.type(),
                    recommendation.symbol(), account.environment().label(), clientOrderId);
            accounts.invalidate(account);
            return new SubmissionResult.LegResult(
                    leg.side().name(), leg.type().name(), leg.timeInForce().name(),
                    leg.quantity().toDisplay(),
                    leg.limitPrice().map(p -> p.toDisplay()),
                    clientOrderId,
                    Optional.ofNullable(response == null ? null : response.getOrderId()),
                    // placeOrder returns ids only, never a status - the order is accepted, not
                    // necessarily executed.
                    "SUBMITTED",
                    Optional.empty(), Optional.empty(), Optional.empty());
        } catch (RuntimeException e) {
            log.warn("Order rejected for {} in {}: {}", recommendation.symbol(),
                    account.environment().label(), e.getMessage());
            return new SubmissionResult.LegResult(
                    leg.side().name(), leg.type().name(), leg.timeInForce().name(),
                    leg.quantity().toDisplay(),
                    leg.limitPrice().map(p -> p.toDisplay()),
                    clientOrderId, Optional.empty(), "FAILED",
                    Optional.empty(), Optional.empty(), Optional.of(describe(e)));
        }
    }

    /** Polls the entry until it fills or the window elapses. */
    private SubmissionResult.LegResult awaitFill(TradeClientV3 client, BrokerAccount account,
                                                 SubmissionResult.LegResult leg) {
        SubmissionResult.LegResult latest = leg;
        for (int attempt = 0; attempt < FILL_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(FILL_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return latest;
            }
            Optional<SubmissionResult.LegResult> updated = status(client, account, latest);
            if (updated.isPresent()) {
                latest = updated.get();
                if (latest.isFilled() || "CANCELLED".equalsIgnoreCase(latest.status())
                        || "FAILED".equalsIgnoreCase(latest.status())) {
                    return latest;
                }
            }
        }
        return latest;
    }

    private Optional<SubmissionResult.LegResult> status(TradeClientV3 client, BrokerAccount account,
                                                        SubmissionResult.LegResult leg) {
        try {
            OrderHistory history = client.getOrderDetails(account.accountId(), leg.clientOrderId());
            if (history == null || history.getOrders() == null || history.getOrders().isEmpty()) {
                return Optional.empty();
            }
            // The symbol and status live on the nested legs, not on OrderHistory itself.
            NOrderItem item = history.getOrders().get(0);
            return Optional.of(new SubmissionResult.LegResult(
                    leg.side(), leg.orderType(), leg.timeInForce(), leg.quantity(),
                    leg.limitPrice(), leg.clientOrderId(),
                    Optional.ofNullable(item.getOrderId()).or(leg::orderId),
                    item.getStatus() == null ? leg.status() : item.getStatus(),
                    decimal(item.getFilledQuantity()),
                    decimal(item.getFilledPrice()),
                    Optional.empty()));
        } catch (RuntimeException e) {
            log.debug("Could not read order {}: {}", leg.clientOrderId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String instrumentTypeFor(String universe) {
        TradableUniverse resolved;
        try {
            resolved = TradableUniverse.valueOf(universe.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            resolved = TradableUniverse.EQUITY;
        }
        return switch (resolved) {
            case EQUITY -> InstrumentSuperType.EQUITY.name();
            case CRYPTO -> InstrumentSuperType.CRYPTO.name();
            case EVENT -> InstrumentSuperType.EVENT.name();
            case FUTURES -> InstrumentSuperType.FUTURES.name();
        };
    }

    private static Optional<BigDecimal> decimal(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String describe(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
        }
        return e.getClass().getSimpleName();
    }
}
