package dev.aperture.account;

import com.webull.openapi.trade.TradeClientV3;
import com.webull.openapi.trade.response.v3.Account;
import com.webull.openapi.trade.response.v3.AccountAssetInfo;
import com.webull.openapi.trade.response.v3.AccountBalanceInfo;
import com.webull.openapi.trade.response.v3.AccountPositionsInfo;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import dev.aperture.config.ApertureProperties;
import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.time.MarketClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads the account holder's Webull accounts, balances and positions in both environments.
 *
 * <p>Read-only. There is no order-placing method here at all - not a disabled one, not one behind
 * a flag. Submission lives in {@code OrderService} behind its own gate, so nothing that merely
 * needs to display an account can reach the venue by accident.
 *
 * <p>Account lists are cached briefly. The picker asks for them on every page load and they change
 * on the order of never, so a short cache turns a per-request network call into a per-minute one
 * without making the UI stale in any way a user would notice.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final Duration ACCOUNT_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration BALANCE_CACHE_TTL = Duration.ofSeconds(10);

    private final WebullClientHolder holder;
    private final MarketClock clock;
    private final ApertureProperties.Webull config;

    private final Map<TradingEnvironment, Cached<List<BrokerAccount>>> accountCache =
            new EnumMap<>(TradingEnvironment.class);
    private final Map<String, Cached<AccountBalance>> balanceCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<AccountPosition>>> positionCache = new ConcurrentHashMap<>();

    public AccountService(WebullClientHolder holder, MarketClock clock,
                          ApertureProperties properties) {
        this.holder = holder;
        this.clock = clock;
        this.config = properties.webull();
    }

    /** The default environment, from configuration. Sandbox unless deliberately changed. */
    public TradingEnvironment defaultEnvironment() {
        return config.defaultTradingEnvironment();
    }

    /** Whether live order submission is permitted, and if not, why not. */
    public boolean liveOrdersPermitted() {
        return config.liveOrdersPermitted();
    }

    public String liveOrderBlockReason() {
        return config.liveOrderBlockReason();
    }

    /** Accounts in one environment. Empty when that environment is not provisioned. */
    public List<BrokerAccount> accounts(TradingEnvironment environment) {
        Cached<List<BrokerAccount>> cached = accountCache.get(environment);
        if (cached != null && cached.isFresh(clock.now(), ACCOUNT_CACHE_TTL)) {
            return cached.value();
        }
        Optional<TradeClientV3> client = holder.tradeClient(environment);
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            List<Account> vendorAccounts = client.get().listAccount();
            List<BrokerAccount> accounts = new ArrayList<>();
            if (vendorAccounts != null) {
                for (Account account : vendorAccounts) {
                    accounts.add(new BrokerAccount(
                            account.getAccountId(),
                            account.getAccountNumber(),
                            account.getAccountType(),
                            account.getAccountClass(),
                            account.getAccountLabel(),
                            environment));
                }
            }
            accountCache.put(environment, new Cached<>(accounts, clock.now()));
            return accounts;
        } catch (RuntimeException e) {
            log.warn("Could not list {} accounts: {}", environment.label(), e.getMessage());
            return List.of();
        }
    }

    /** Every account across both environments, for the picker. */
    public Map<TradingEnvironment, List<BrokerAccount>> allAccounts() {
        Map<TradingEnvironment, List<BrokerAccount>> out = new LinkedHashMap<>();
        for (TradingEnvironment environment : TradingEnvironment.values()) {
            out.put(environment, accounts(environment));
        }
        return out;
    }

    public Optional<BrokerAccount> findAccount(TradingEnvironment environment, String accountId) {
        return accounts(environment).stream()
                .filter(a -> a.accountId().equals(accountId))
                .findFirst();
    }

    /** The account to use when none is selected: the first in the environment. */
    public Optional<BrokerAccount> defaultAccount(TradingEnvironment environment) {
        List<BrokerAccount> accounts = accounts(environment);
        return accounts.isEmpty() ? Optional.empty() : Optional.of(accounts.get(0));
    }

    public AccountBalance balance(BrokerAccount account) {
        String key = account.key();
        Cached<AccountBalance> cached = balanceCache.get(key);
        if (cached != null && cached.isFresh(clock.now(), BALANCE_CACHE_TTL)) {
            return cached.value();
        }
        Optional<TradeClientV3> client = holder.tradeClient(account.environment());
        if (client.isEmpty()) {
            return AccountBalance.unavailable(account.accountId(), account.environment(), clock.now());
        }
        try {
            AccountBalanceInfo info = client.get().balanceAccount(account.accountId());
            AccountBalance balance = toBalance(account, info);
            balanceCache.put(key, new Cached<>(balance, clock.now()));
            return balance;
        } catch (RuntimeException e) {
            log.warn("Could not read balance for {} ({}): {}",
                    account.maskedNumber(), account.environment().label(), e.getMessage());
            return AccountBalance.unavailable(account.accountId(), account.environment(), clock.now());
        }
    }

    private AccountBalance toBalance(BrokerAccount account, AccountBalanceInfo info) {
        if (info == null) {
            return AccountBalance.unavailable(account.accountId(), account.environment(), clock.now());
        }
        // Buying power lives on the per-currency asset breakdown rather than the top level; take
        // the account's own currency, falling back to the first entry for a single-currency account.
        Optional<AccountAssetInfo> asset = Optional.ofNullable(info.getAccountCurrencyAssets())
                .filter(list -> !list.isEmpty())
                .map(list -> list.stream()
                        .filter(a -> info.getTotalAssetCurrency() == null
                                || info.getTotalAssetCurrency().equalsIgnoreCase(a.getCurrency()))
                        .findFirst()
                        .orElse(list.get(0)));

        return new AccountBalance(
                account.accountId(),
                account.environment(),
                info.getTotalAssetCurrency(),
                money(info.getTotalNetLiquidationValue()),
                money(info.getTotalCashBalance()),
                money(info.getTotalMarketValue()),
                asset.flatMap(a -> money(a.getBuyingPower())),
                money(info.getTotalUnrealizedProfitLoss()),
                money(info.getTotalDayProfitLoss()),
                money(info.getMaintenanceMargin()),
                Optional.ofNullable(info.getDayTradesLeft()),
                clock.now());
    }

    public List<AccountPosition> positions(BrokerAccount account) {
        String key = account.key();
        Cached<List<AccountPosition>> cached = positionCache.get(key);
        if (cached != null && cached.isFresh(clock.now(), BALANCE_CACHE_TTL)) {
            return cached.value();
        }
        Optional<TradeClientV3> client = holder.tradeClient(account.environment());
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            List<AccountPositionsInfo> vendorPositions =
                    client.get().positionsAccount(account.accountId());
            List<AccountPosition> positions = new ArrayList<>();
            if (vendorPositions != null) {
                for (AccountPositionsInfo position : vendorPositions) {
                    toPosition(position).ifPresent(positions::add);
                }
            }
            positionCache.put(key, new Cached<>(positions, clock.now()));
            return positions;
        } catch (RuntimeException e) {
            log.warn("Could not read positions for {} ({}): {}",
                    account.maskedNumber(), account.environment().label(), e.getMessage());
            return List.of();
        }
    }

    private Optional<AccountPosition> toPosition(AccountPositionsInfo info) {
        if (info == null || info.getSymbol() == null) {
            return Optional.empty();
        }
        BigDecimal quantity = decimal(info.getQuantity());
        if (quantity == null || quantity.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(new AccountPosition(
                info.getPositionId(),
                info.getSymbol(),
                info.getSymbolName(),
                Quantity.of(quantity),
                Quantity.of(orZero(decimal(info.getAvailableQuantity()))),
                Price.of(orZero(decimal(info.getCostPrice()))),
                Price.of(orZero(decimal(info.getLastPrice()))),
                money(info.getMarketValue()),
                money(info.getUnrealizedProfitLoss()),
                Optional.ofNullable(decimal(info.getUnrealizedProfitLossRate())),
                info.getInstrumentType(),
                info.getCurrency()));
    }

    /** Drops cached balances and positions for an account, after an order changes them. */
    public void invalidate(BrokerAccount account) {
        balanceCache.remove(account.key());
        positionCache.remove(account.key());
    }

    private static Optional<Money> money(String value) {
        BigDecimal amount = decimal(value);
        return amount == null ? Optional.empty() : Optional.of(Money.usd(amount));
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

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** A value with the time it was fetched. */
    private record Cached<T>(T value, Instant fetchedAt) {
        boolean isFresh(Instant now, Duration ttl) {
            return fetchedAt.plus(ttl).isAfter(now);
        }
    }
}
