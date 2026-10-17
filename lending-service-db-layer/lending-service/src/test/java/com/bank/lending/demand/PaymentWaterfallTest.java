package com.bank.lending.demand;

import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.demand.util.PaymentWaterfall.Component;
import com.bank.lending.demand.util.PaymentWaterfall.Due;
import com.bank.lending.demand.util.PaymentWaterfall.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure Java: the order in which a payment is applied. No Spring, no database. */
class PaymentWaterfallTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    /** Instalment no, interest, principal; due on the 5th of consecutive months. */
    private static Due due(long id, int no, String penalty, String interest, String principal) {
        return new Due(id, no, LocalDate.of(2026, 7 + no, 5), n(penalty), n(interest), n(principal));
    }

    @Test
    void interestIsPaidBeforePrincipalWithinOneDemand() {
        Result r = PaymentWaterfall.apply(List.of(due(1, 1, "0", "800.00", "9200.00")), n("5000.00"));

        assertThat(r.applied(Component.INTEREST)).isEqualByComparingTo("800.00");
        assertThat(r.applied(Component.PRINCIPAL)).isEqualByComparingTo("4200.00");
        assertThat(r.surplus()).isEqualByComparingTo("0");
    }

    @Test
    void theOldestDemandIsClearedBeforeANewerOneIsTouched() {
        List<Due> dues = List.of(due(1, 1, "0", "800.00", "9200.00"), due(2, 2, "0", "700.00", "9300.00"));

        Result r = PaymentWaterfall.apply(dues, n("10500.00"));

        // instalment 1 in full (10,000), then 500 of instalment 2's interest
        assertThat(r.lines()).extracting(PaymentWaterfall.Line::installmentNumber).containsExactly(1, 1, 2);
        assertThat(r.lines().get(2).component()).isEqualTo(Component.INTEREST);
        assertThat(r.lines().get(2).amount()).isEqualByComparingTo("500.00");
        assertThat(r.surplus()).isEqualByComparingTo("0");
    }

    @Test
    void penalInterestOnEveryDemandIsPaidBeforeAnyInterestOrPrincipal() {
        List<Due> dues = List.of(due(1, 1, "120.00", "800.00", "9200.00"), due(2, 2, "40.00", "700.00", "9300.00"));

        Result r = PaymentWaterfall.apply(dues, n("200.00"));

        assertThat(r.applied(Component.PENALTY)).isEqualByComparingTo("160.00");
        assertThat(r.applied(Component.INTEREST)).isEqualByComparingTo("40.00");
        assertThat(r.applied(Component.PRINCIPAL)).isEqualByComparingTo("0");
        assertThat(r.lines().get(2).installmentNumber()).isEqualTo(1);
    }

    @Test
    void moneyBeyondEverythingOwedComesBackAsSurplus() {
        Result r = PaymentWaterfall.apply(List.of(due(1, 1, "0", "800.00", "9200.00")), n("12000.00"));

        assertThat(r.totalApplied()).isEqualByComparingTo("10000.00");
        assertThat(r.surplus()).isEqualByComparingTo("2000.00");
    }

    @Test
    void withNothingOwedTheWholeAmountIsSurplus() {
        Result r = PaymentWaterfall.apply(List.of(), n("750.00"));

        assertThat(r.lines()).isEmpty();
        assertThat(r.surplus()).isEqualByComparingTo("750.00");
    }

    @Test
    void zeroComponentsProduceNoLines() {
        Result r = PaymentWaterfall.apply(List.of(due(1, 1, "0", "0", "500.00")), n("500.00"));

        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).component()).isEqualTo(Component.PRINCIPAL);
    }

    @Test
    void anAmountThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> PaymentWaterfall.apply(List.of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentWaterfall.apply(List.of(), n("-5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatWasAppliedPlusTheSurplusAlwaysEqualsWhatWasPaid() {
        List<Due> dues = List.of(due(1, 1, "33.33", "800.10", "9200.07"), due(2, 2, "0", "700.55", "9300.45"));
        for (String paid : List.of("0.01", "33.33", "100.00", "9999.99", "10033.50", "25000.00")) {
            Result r = PaymentWaterfall.apply(dues, n(paid));
            assertThat(r.totalApplied().add(r.surplus())).isEqualByComparingTo(paid);
        }
    }
}
