package dev.aperture.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ValueTypeTest {

    @Nested
    class MoneyTest {

        @Test
        @DisplayName("holds more precision than cents so fees do not drift")
        void keepsSubCentPrecision() {
            // A third of a cent, repeated three times, must come back to the cent.
            Money third = Money.usd(new BigDecimal("0.003333"));
            Money total = third.plus(third).plus(third);
            assertThat(total.toDisplay()).isEqualByComparingTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("rounds to cents only at the display boundary")
        void displayRoundsToCents() {
            assertThat(Money.usd(new BigDecimal("1.005")).toDisplay())
                    .isEqualByComparingTo(new BigDecimal("1.00"));  // banker's rounding
            assertThat(Money.usd(new BigDecimal("1.015")).toDisplay())
                    .isEqualByComparingTo(new BigDecimal("1.02"));
        }

        @Test
        @DisplayName("refuses to combine different currencies")
        void rejectsCurrencyMismatch() {
            Money usd = Money.usd(10);
            Money eur = new Money(BigDecimal.TEN, "EUR");
            assertThatThrownBy(() -> usd.plus(eur))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR");
        }

        @Test
        @DisplayName("arithmetic behaves")
        void arithmetic() {
            assertThat(Money.usd(10).minus(Money.usd(3)).toDisplay())
                    .isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(Money.usd(10).negated().isNegative()).isTrue();
            assertThat(Money.usd(-5).abs().isPositive()).isTrue();
            assertThat(Money.zero().isZero()).isTrue();
        }
    }

    @Nested
    class PriceTest {

        @Test
        @DisplayName("a price times a quantity is money")
        void priceTimesQuantityIsMoney() {
            // The types differ so that adding a price to a cash balance cannot compile.
            Money notional = Price.of(100.50).times(Quantity.of(10));
            assertThat(notional.toDisplay()).isEqualByComparingTo(new BigDecimal("1005.00"));
        }

        @Test
        @DisplayName("survives a split factor applied and reversed")
        void survivesSplitRoundTrip() {
            // Six decimal places exist so a /10 followed by a *10 returns the original, rather
            // than the 32.99 a cent-scaled price would give back.
            Price original = Price.of(329.97);
            Price split = original.scaledBy(new BigDecimal("0.1"));
            Price restored = split.scaledBy(BigDecimal.TEN);
            assertThat(restored.toDisplay()).isEqualByComparingTo(original.toDisplay());
        }

        @Test
        @DisplayName("cannot be negative")
        void rejectsNegative() {
            assertThatThrownBy(() -> Price.of(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("subtraction floors at zero rather than throwing")
        void subtractionFloorsAtZero() {
            // Used for spreads, where a crossed book would otherwise throw mid-render.
            assertThat(Price.of(10).minus(Price.of(12)).isZero()).isTrue();
        }
    }

    @Nested
    class QuantityTest {

        @Test
        @DisplayName("is signed, so a short position is a negative quantity")
        void signed() {
            assertThat(Quantity.of(-150).isNegative()).isTrue();
            assertThat(Quantity.of(-150).abs()).isEqualTo(Quantity.of(150));
        }

        @Test
        @DisplayName("supports fractional shares from an odd-lot split")
        void fractional() {
            // 101 shares through a 3-for-2 is 151.5.
            Quantity after = Quantity.of(101).scaledBy(new BigDecimal("1.5"));
            assertThat(after.value()).isEqualByComparingTo(new BigDecimal("151.5"));
        }

        @Test
        @DisplayName("display strips trailing zeros")
        void displayStripsZeros() {
            assertThat(Quantity.of(100).toDisplay().toPlainString()).isEqualTo("100");
        }
    }
}
