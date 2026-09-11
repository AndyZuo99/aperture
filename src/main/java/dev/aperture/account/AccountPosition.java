package dev.aperture.account;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.common.Quantity;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * A holding in a Webull account, as the broker reports it.
 *
 * <p>Deliberately the broker's view rather than a locally reconstructed one. The broker is
 * authoritative about what is held; recomputing it from a local fill history would introduce a
 * second answer to a question that already has one, and the interesting bugs all live in the gap
 * between them.
 */
public record AccountPosition(
        String positionId,
        String symbol,
        String symbolName,
        Quantity quantity,
        Quantity availableQuantity,
        Price costPrice,
        Price lastPrice,
        Optional<Money> marketValue,
        Optional<Money> unrealizedProfitLoss,
        Optional<BigDecimal> unrealizedProfitLossPercent,
        String instrumentType,
        String currency) {

    public AccountPosition {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(quantity, "quantity");
        symbol = symbol.toUpperCase();
        symbolName = symbolName == null ? "" : symbolName;
        instrumentType = instrumentType == null ? "" : instrumentType;
        currency = currency == null || currency.isBlank() ? "USD" : currency;
    }

    public boolean isLong() {
        return quantity.isPositive();
    }

    public boolean isShort() {
        return quantity.isNegative();
    }

    /** Cost basis: what was paid for the holding. */
    public Money costBasis() {
        return costPrice.times(quantity.abs());
    }
}
