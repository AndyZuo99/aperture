package dev.aperture.account;

import dev.aperture.common.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An account's cash and margin state at a point in time.
 *
 * <p>Every figure is optional because the vendor omits fields that do not apply - a cash account
 * has no maintenance margin - and a missing figure must not be rendered as zero. "No margin
 * requirement" and "a margin requirement of nothing" look identical once a null becomes 0.
 */
public record AccountBalance(
        String accountId,
        TradingEnvironment environment,
        String currency,
        Optional<Money> netLiquidationValue,
        Optional<Money> totalCash,
        Optional<Money> marketValue,
        Optional<Money> buyingPower,
        Optional<Money> unrealizedProfitLoss,
        Optional<Money> dayProfitLoss,
        Optional<Money> maintenanceMargin,
        Optional<String> dayTradesLeft,
        Instant asOf) {

    public AccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(asOf, "asOf");
        currency = currency == null || currency.isBlank() ? "USD" : currency;
    }

    /** An empty balance, used when the vendor is unreachable, so the UI has something to render. */
    public static AccountBalance unavailable(String accountId, TradingEnvironment environment,
                                             Instant asOf) {
        return new AccountBalance(accountId, environment, "USD",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), asOf);
    }

    public boolean isAvailable() {
        return netLiquidationValue.isPresent() || totalCash.isPresent();
    }
}
