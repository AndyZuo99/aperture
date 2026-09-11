package dev.aperture.instrument;

import java.util.Objects;
import java.util.Optional;

/**
 * A tradable security.
 *
 * <p>{@code vendorInstrumentId} is Webull's own identifier, kept so that a symbol rename on their
 * side can be detected rather than silently changing what we think we hold.
 */
public record Instrument(
        InstrumentId id,
        String primarySymbol,
        String name,
        SecurityType type,
        String exchangeCode,
        String currency,
        Optional<String> vendorInstrumentId,
        boolean active) {

    public Instrument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(vendorInstrumentId, "vendorInstrumentId");
        primarySymbol = Objects.requireNonNull(primarySymbol, "primarySymbol").toUpperCase();
        exchangeCode = exchangeCode == null ? "" : exchangeCode.toUpperCase();
        currency = currency == null ? "USD" : currency.toUpperCase();
    }

    public static Instrument equity(InstrumentId id, String symbol, String name) {
        return new Instrument(id, symbol, name, SecurityType.COMMON_STOCK, "NASDAQ", "USD",
                Optional.empty(), true);
    }

    public boolean isTradable() {
        return active && type.isTradable();
    }

    public Instrument withVendorId(String vendorId) {
        return new Instrument(id, primarySymbol, name, type, exchangeCode, currency,
                Optional.ofNullable(vendorId), active);
    }

    public Instrument renamedTo(String newSymbol) {
        return new Instrument(id, newSymbol, name, type, exchangeCode, currency,
                vendorInstrumentId, active);
    }
}
