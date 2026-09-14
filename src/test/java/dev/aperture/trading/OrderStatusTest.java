package dev.aperture.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vendor order statuses, mapped to the outcomes a trader acts on.
 *
 * <p>The vendor's set is open-ended and not documented exhaustively, so the mapping has to fail
 * safe: a status this build has never seen must not be guessed into {@code FILLED}.
 */
class OrderStatusTest {

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "FILLED,          FILLED",
            "CANCELLED,       CANCELLED",
            "CANCELED,        CANCELLED",
            "SUBMITTED,       WORKING",
            "PENDING,         WORKING",
            "QUEUED,          WORKING",
            "PARTIAL_FILLED,  PARTIALLY_FILLED",
            "REJECTED,        REJECTED",
            "FAILED,          REJECTED",
            "filled,          FILLED",
            "'  SUBMITTED  ', WORKING",
    })
    void parsesVendorStatuses(String vendor, OrderStatus expected) {
        assertThat(OrderStatus.parse(vendor)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "SOME_NEW_STATUS", "PENDING_REPLACE_XYZ"})
    @DisplayName("anything unrecognised is UNKNOWN, never a guess")
    void unrecognisedIsUnknown(String vendor) {
        assertThat(OrderStatus.parse(vendor)).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(OrderStatus.parse(vendor).isFilled()).isFalse();
        assertThat(OrderStatus.parse(vendor).isOpen()).isFalse();
    }

    @Test
    @DisplayName("only working and partially filled orders are open")
    void openStatuses() {
        // "Open" means it can still execute, which is what decides whether a position is covered.
        assertThat(OrderStatus.WORKING.isOpen()).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.isOpen()).isTrue();
        assertThat(OrderStatus.FILLED.isOpen()).isFalse();
        assertThat(OrderStatus.CANCELLED.isOpen()).isFalse();
        assertThat(OrderStatus.REJECTED.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a partial fill is not counted as filled")
    void partialIsNotFilled() {
        // It still has quantity live at the venue; counting it as complete would overstate the
        // fill rate and understate what is still exposed.
        assertThat(OrderStatus.PARTIALLY_FILLED.isFilled()).isFalse();
        assertThat(OrderStatus.FILLED.isFilled()).isTrue();
    }
}
