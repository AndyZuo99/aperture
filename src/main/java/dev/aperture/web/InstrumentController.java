package dev.aperture.web;

import dev.aperture.account.AccountService;
import dev.aperture.account.BrokerAccount;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.instrument.InstrumentCatalog;
import dev.aperture.instrument.TradableInstrument;
import dev.aperture.instrument.TradableUniverse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the selected account can actually trade.
 *
 * <p>The universe is derived from the account rather than chosen by the caller, because it is a
 * property of the account and not a preference: a futures account cannot buy equities no matter
 * what the UI asks for. An explicit {@code universe} parameter is accepted as an override, for
 * browsing an asset class without holding an account of that type.
 */
@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    /** Enough to browse, small enough that the browser stays responsive. */
    private static final int DEFAULT_LIMIT = 250;

    private final InstrumentCatalog catalog;
    private final AccountService accounts;

    public InstrumentController(InstrumentCatalog catalog, AccountService accounts) {
        this.catalog = catalog;
        this.accounts = accounts;
    }

    @GetMapping("/tradable")
    public ApiDtos.TradableUniverseView tradable(
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String universe,
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "") String group,
            @RequestParam(required = false, defaultValue = "true") boolean tradableOnly,
            @RequestParam(required = false, defaultValue = "0") int limit) {

        TradingEnvironment target = environment == null || environment.isBlank()
                ? accounts.defaultEnvironment()
                : TradingEnvironment.parseOrSandbox(environment);

        Optional<BrokerAccount> account = accountId == null || accountId.isBlank()
                ? accounts.defaultAccount(target)
                : accounts.findAccount(target, accountId);

        // An explicit universe wins; otherwise the account's class decides.
        TradableUniverse resolved = parseUniverse(universe)
                .orElseGet(() -> account
                        .map(a -> TradableUniverse.forAccountClass(a.accountClass()))
                        .orElse(TradableUniverse.EQUITY));

        InstrumentCatalog.Listing listing = catalog.listing(
                resolved, q, group, tradableOnly, limit > 0 ? limit : DEFAULT_LIMIT);

        boolean marginAccount =
                isMarginAccount(account.map(BrokerAccount::accountType).orElse(null));

        List<ApiDtos.TradableInstrumentView> rows = new ArrayList<>();
        for (TradableInstrument instrument : listing.instruments()) {
            rows.add(new ApiDtos.TradableInstrumentView(
                    instrument.symbol(), instrument.name(), instrument.group(),
                    instrument.status(), instrument.tradable(),
                    instrument.attributesFor(marginAccount)));
        }

        List<ApiDtos.GroupCountView> groups = new ArrayList<>();
        catalog.groupCounts(resolved).forEach((groupName, count) ->
                groups.add(new ApiDtos.GroupCountView(groupName, count)));

        return new ApiDtos.TradableUniverseView(
                resolved.name(),
                resolved.label(),
                resolved.description(),
                account.map(BrokerAccount::accountId).orElse(null),
                account.map(BrokerAccount::displayName).orElse(null),
                account.map(BrokerAccount::accountType).orElse(null),
                account.map(BrokerAccount::accountClass).orElse(null),
                target.name(),
                listing.isAvailable(),
                listing.unavailableReason(),
                listing.matching(),
                listing.total(),
                listing.truncated(),
                columnsOf(rows),
                rows,
                groups);
    }

    /** A cash account cannot borrow, so leverage attributes are suppressed for it. */
    private static boolean isMarginAccount(String accountType) {
        return accountType != null && accountType.toUpperCase().contains("MARGIN");
    }

    /**
     * The attribute columns present in this page, in first-seen order.
     *
     * <p>A union rather than the first row's keys: the vendor omits attributes it has no value
     * for, so a single row is not representative and a fixed column list would either show empty
     * columns or hide populated ones. Derived from the rows <em>after</em> filtering, so a
     * suppressed attribute does not leave an empty column behind.
     */
    private static List<String> columnsOf(List<ApiDtos.TradableInstrumentView> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (ApiDtos.TradableInstrumentView row : rows) {
            columns.addAll(row.attributes().keySet());
        }
        return List.copyOf(columns);
    }

    private static Optional<TradableUniverse> parseUniverse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TradableUniverse.valueOf(value.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown universe: " + value
                    + ". Expected EQUITY, FUTURES, CRYPTO or EVENT.");
        }
    }
}
