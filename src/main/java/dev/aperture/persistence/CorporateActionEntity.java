package dev.aperture.persistence;

import dev.aperture.common.Money;
import dev.aperture.corporate.ActionSource;
import dev.aperture.corporate.CorporateAction;
import dev.aperture.corporate.RecordedAction;
import dev.aperture.instrument.InstrumentId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A persisted corporate action.
 *
 * <p>Stored flat with nullable columns per variant rather than as a JPA inheritance hierarchy.
 * Four variants with three or four fields between them do not justify either a join per read or a
 * discriminator hierarchy, and a flat row is far easier to inspect when something looks wrong in
 * a chart.
 */
@Entity(name = "CorporateActionEntity")
@Table(name = "corporate_actions")
public class CorporateActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id", nullable = false)
    private String instrumentId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Column(name = "new_shares")
    private BigDecimal newShares;

    @Column(name = "old_shares")
    private BigDecimal oldShares;

    @Column(name = "shares_per_share")
    private BigDecimal sharesPerShare;

    @Column(name = "amount_per_share")
    private BigDecimal amountPerShare;

    @Column(name = "pay_date")
    private LocalDate payDate;

    @Column(name = "old_symbol")
    private String oldSymbol;

    @Column(name = "new_symbol")
    private String newSymbol;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    protected CorporateActionEntity() {
        // For JPA.
    }

    public static CorporateActionEntity from(RecordedAction recorded) {
        CorporateActionEntity entity = new CorporateActionEntity();
        CorporateAction action = recorded.action();
        entity.instrumentId = action.instrumentId().value();
        entity.exDate = action.exDate();
        entity.source = recorded.source().name();
        entity.recordedAt = recorded.recordedAt();
        entity.dedupeKey = recorded.dedupeKey();

        switch (action) {
            case CorporateAction.Split split -> {
                entity.actionType = "SPLIT";
                entity.newShares = split.newShares();
                entity.oldShares = split.oldShares();
            }
            case CorporateAction.StockDividend stock -> {
                entity.actionType = "STOCK_DIVIDEND";
                entity.sharesPerShare = stock.sharesPerShare();
            }
            case CorporateAction.CashDividend cash -> {
                entity.actionType = "CASH_DIVIDEND";
                entity.amountPerShare = cash.amountPerShare().amount();
                entity.payDate = cash.payDate();
            }
            case CorporateAction.SymbolChange change -> {
                entity.actionType = "SYMBOL_CHANGE";
                entity.oldSymbol = change.oldSymbol();
                entity.newSymbol = change.newSymbol();
            }
        }
        return entity;
    }

    /** Rebuilds the domain object. Throws on an unknown type rather than silently dropping it. */
    public RecordedAction toDomain() {
        InstrumentId instrument = InstrumentId.of(instrumentId);
        CorporateAction action = switch (actionType) {
            case "SPLIT" -> new CorporateAction.Split(instrument, exDate, newShares, oldShares);
            case "STOCK_DIVIDEND" ->
                    new CorporateAction.StockDividend(instrument, exDate, sharesPerShare);
            case "CASH_DIVIDEND" -> new CorporateAction.CashDividend(
                    instrument, exDate, payDate == null ? exDate : payDate,
                    Money.usd(amountPerShare));
            case "SYMBOL_CHANGE" ->
                    new CorporateAction.SymbolChange(instrument, exDate, oldSymbol, newSymbol);
            default -> throw new IllegalStateException(
                    "Unknown persisted corporate action type: " + actionType);
        };
        return new RecordedAction(action, ActionSource.valueOf(source), recordedAt);
    }

    public Long getId() {
        return id;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }
}
