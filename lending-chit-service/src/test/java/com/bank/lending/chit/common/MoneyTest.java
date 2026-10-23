package com.bank.lending.chit.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void scalesHalfUpToTwoPlaces() {
        assertEquals(new BigDecimal("10.01"), Money.scale(new BigDecimal("10.005")));
        assertEquals(new BigDecimal("10.00"), Money.scale(new BigDecimal("10.004")));
        assertEquals(new BigDecimal("0.00"), Money.scale(new BigDecimal("0")));
    }

    @Test
    void percentOfRoundsOnceAtTheEnd() {
        assertEquals(new BigDecimal("5000.00"), Money.percentOf(new BigDecimal("100000.00"), new BigDecimal("5")));
        // 0.333 percent of 100.00 = 0.333 -> 0.33
        assertEquals(new BigDecimal("0.33"), Money.percentOf(new BigDecimal("100.00"), new BigDecimal("0.333")));
    }

    @Test
    void paiseRoundTrip() {
        assertEquals(1500007L, Money.toPaise(new BigDecimal("15000.07")));
        assertEquals(new BigDecimal("15000.07"), Money.ofPaise(1500007L));
        assertEquals(new BigDecimal("0.05"), Money.ofPaise(5L));
    }

    @Test
    void toPaiseRefusesMoreThanTwoDecimals() {
        assertThrows(ArithmeticException.class, () -> Money.toPaise(new BigDecimal("1.234")));
    }
}
