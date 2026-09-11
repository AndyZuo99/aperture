package dev.aperture.corporate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.instrument.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The per-action factors, independent of how a series is walked. */
class CorporateActionTest {

    private static final InstrumentId ID = InstrumentId.of("TEST");
    private static final LocalDate EX = LocalDate.parse("2026-06-10");

    @Test
    @DisplayName("a forward split divides price and multiplies share count")
    void forwardSplitFactors() {
        CorporateAction.Split split = CorporateAction.Split.forward(ID, EX, 10, 1);

        assertThat(split.priceAdjustmentFactor(Price.of(100)))
                .isCloseTo(new BigDecimal("0.1"), within(new BigDecimal("0.000001")));
        assertThat(split.quantityAdjustmentFactor())
                .isCloseTo(BigDecimal.TEN, within(new BigDecimal("0.000001")));
        assertThat(split.isReverse()).isFalse();
        assertThat(split.affectsShareCount()).isTrue();
        assertThat(split.describe()).isEqualTo("Split 10-for-1");
    }

    @Test
    @DisplayName("a reverse split does the opposite")
    void reverseSplitFactors() {
        CorporateAction.Split split = CorporateAction.Split.forward(ID, EX, 1, 8);

        assertThat(split.priceAdjustmentFactor(Price.of(100)))
                .isCloseTo(new BigDecimal("8"), within(new BigDecimal("0.000001")));
        assertThat(split.isReverse()).isTrue();
        assertThat(split.describe()).isEqualTo("Reverse split 1-for-8");
    }

    @Test
    @DisplayName("a split ratio must be positive")
    void rejectsNonPositiveRatio() {
        assertThatThrownBy(() -> new CorporateAction.Split(ID, EX, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a cash dividend does not change the share count")
    void cashDividendLeavesShareCountAlone() {
        CorporateAction.CashDividend dividend = new CorporateAction.CashDividend(
                ID, EX, EX.plusDays(14), Money.usd(1));

        assertThat(dividend.quantityAdjustmentFactor()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(dividend.affectsShareCount()).isFalse();
        assertThat(dividend.priceAdjustmentFactor(Price.of(100)))
                .isCloseTo(new BigDecimal("0.99"), within(new BigDecimal("0.000001")));
    }

    @Test
    @DisplayName("a pay date before the ex-date is rejected")
    void rejectsPayDateBeforeExDate() {
        assertThatThrownBy(() -> new CorporateAction.CashDividend(
                ID, EX, EX.minusDays(1), Money.usd(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precedes");
    }

    @Test
    @DisplayName("a stock dividend behaves like a small split")
    void stockDividend() {
        CorporateAction.StockDividend action =
                new CorporateAction.StockDividend(ID, EX, new BigDecimal("0.05"));

        assertThat(action.quantityAdjustmentFactor())
                .isCloseTo(new BigDecimal("1.05"), within(new BigDecimal("0.000001")));
        assertThat(action.affectsShareCount()).isTrue();
    }

    @Test
    @DisplayName("a symbol change is inert on both axes")
    void symbolChangeIsInert() {
        CorporateAction.SymbolChange change =
                new CorporateAction.SymbolChange(ID, EX, "fb", "meta");

        assertThat(change.priceAdjustmentFactor(Price.of(100)))
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(change.quantityAdjustmentFactor()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(change.affectsPrices()).isFalse();
        assertThat(change.describe()).isEqualTo("Symbol change FB to META");
    }

    @Test
    @DisplayName("a policy selects only the action types it covers")
    void policySelection() {
        CorporateAction split = CorporateAction.Split.forward(ID, EX, 2, 1);
        CorporateAction dividend = new CorporateAction.CashDividend(
                ID, EX, EX, Money.usd(1));

        assertThat(AdjustmentPolicy.NONE.applies(split)).isFalse();
        assertThat(AdjustmentPolicy.SPLITS_ONLY.applies(split)).isTrue();
        assertThat(AdjustmentPolicy.SPLITS_ONLY.applies(dividend)).isFalse();
        assertThat(AdjustmentPolicy.TOTAL_RETURN.applies(dividend)).isTrue();
    }
}
