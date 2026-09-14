package dev.aperture.web;

import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.marketdata.WebullClientHolder;
import dev.aperture.trading.OrderHistoryService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts across both environments.
 *
 * <p>There is no "switch environment" endpoint, because the server holds no selected environment.
 * Every request names the environment it wants. A server-side selection would be shared state
 * across browser tabs - two windows open on different environments, one of them silently wrong
 * about which account it is showing.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;
    private final WebullClientHolder holder;
    private final OrderHistoryService orderHistory;
    private final ApiMapper mapper;

    public AccountController(AccountService accounts, WebullClientHolder holder,
                             OrderHistoryService orderHistory, ApiMapper mapper) {
        this.accounts = accounts;
        this.holder = holder;
        this.orderHistory = orderHistory;
        this.mapper = mapper;
    }

    /** Both environments with their accounts, and why an empty one is empty. */
    @GetMapping("/environments")
    public List<ApiDtos.EnvironmentView> environments() {
        List<ApiDtos.EnvironmentView> views = new ArrayList<>();
        for (TradingEnvironment environment : TradingEnvironment.values()) {
            List<BrokerAccount> found = accounts.accounts(environment);
            views.add(new ApiDtos.EnvironmentView(
                    environment.name(),
                    environment.label(),
                    !found.isEmpty(),
                    found.isEmpty() ? holder.unavailableReason(environment).orElse(null) : null,
                    found.stream().map(mapper::toAccountView).toList()));
        }
        return views;
    }

    /** Balances and positions for one account. */
    @GetMapping("/{environment}/{accountId}")
    public ResponseEntity<ApiDtos.AccountDetailView> detail(@PathVariable String environment,
                                                            @PathVariable String accountId) {
        TradingEnvironment target = TradingEnvironment.parseOrSandbox(environment);
        return accounts.findAccount(target, accountId)
                .map(account -> ResponseEntity.ok(mapper.toAccountDetailView(
                        account, accounts.balance(account), accounts.positions(account))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Order history and execution metrics for one account, in either environment.
     *
     * <p>Per account rather than per environment: the vendor rate-limits, and asking for five
     * accounts in a row returns {@code Too many requests}. The panel only ever shows the selected
     * account, so only that account is fetched.
     */
    @GetMapping("/{environment}/{accountId}/orders")
    public ResponseEntity<ApiDtos.OrderHistoryView> orders(@PathVariable String environment,
                                                           @PathVariable String accountId) {
        TradingEnvironment target = TradingEnvironment.parseOrSandbox(environment);
        return accounts.findAccount(target, accountId)
                .map(account -> {
                    boolean connected = holder.tradeClient(target).isPresent();
                    return ResponseEntity.ok(mapper.toOrderHistoryView(
                            account, orderHistory.orders(account), connected,
                            connected ? "" : "Not connected to " + target.label()
                                    + ": " + holder.unavailableReason(target)
                                            .orElse("no trading client")));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The default account for an environment, so the UI has something to show on first load. */
    @GetMapping("/default")
    public ResponseEntity<ApiDtos.AccountDetailView> defaultAccount(
            @RequestParam(required = false) String environment) {
        TradingEnvironment target = environment == null || environment.isBlank()
                ? accounts.defaultEnvironment()
                : TradingEnvironment.parseOrSandbox(environment);
        return accounts.defaultAccount(target)
                .map(account -> ResponseEntity.ok(mapper.toAccountDetailView(
                        account, accounts.balance(account), accounts.positions(account))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Whether live orders are permitted.
     *
     * <p>Exposed so the UI can render the gate state rather than discovering it by having a
     * submission rejected. A trader should be able to see that they are in a read-only posture
     * before they try to act, not after.
     */
    @GetMapping("/trading-gate")
    public ApiDtos.TradingGateView tradingGate() {
        return new ApiDtos.TradingGateView(
                accounts.liveOrdersPermitted(),
                accounts.liveOrderBlockReason(),
                accounts.defaultEnvironment().name());
    }
}
