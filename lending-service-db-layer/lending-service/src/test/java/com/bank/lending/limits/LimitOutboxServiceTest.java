package com.bank.lending.limits;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LimitOutboxServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    private static final BigDecimal AMOUNT = new BigDecimal("500000.00");

    private static Long confirm(LimitFixture fx, String reservationRef) {
        return fx.outbox.insert(7L, "CONFIRM_DISBURSEMENT", "SANC-7", reservationRef, AMOUNT, "INR", "ONLINE", DAY, null, "lend-confirm-7");
    }

    private static Long repay(LimitFixture fx, long txn) {
        return fx.outbox.insert(7L, "REPAY", "SANC-7", null, new BigDecimal("10000.00"), "INR", "ONLINE", DAY, null, "lend-repay-" + txn);
    }

    private static Long close(LimitFixture fx) {
        return fx.outbox.insert(7L, "CLOSE", "SANC-7", null, null, "INR", "ONLINE", DAY, "FORECLOSED", "lend-close-7");
    }

    @Test
    void aDeliveredRowIsSent() {
        LimitFixture fx = new LimitFixture("live");
        confirm(fx, "RSV-1");

        int n = fx.service.retryDue();

        assertThat(n).isEqualTo(1);
        assertThat(fx.outbox.get(1L).status()).isEqualTo("SENT");
        assertThat(fx.lcm.callsTo("/RSV-1/confirm")).hasSize(1);
    }

    @Test
    void aReleasedReservationIsReplacedByALateUnenforcedOneBecauseTheMoneyIsAlreadyOut() {
        LimitFixture fx = new LimitFixture("live");
        confirm(fx, "RSV-1");
        fx.lcm.error("/RSV-1/confirm", 409, "RESERVATION_RELEASED", "Reservation was released");
        fx.lcm.ok("/utilisations/reserve", "{\"reservationRef\":\"RSV-LATE\"}");

        fx.service.retryDue();

        assertThat(fx.outbox.get(1L).status()).isEqualTo("SENT");
        ScriptedTransport.Call reserve = fx.lcm.callsTo("/utilisations/reserve").get(0);
        assertThat(reserve.body()).contains("\"enforce\":false");
        assertThat(reserve.key()).isEqualTo("lend-confirm-7-late");
        assertThat(fx.lcm.callsTo("/RSV-LATE/confirm")).hasSize(1);
    }

    @Test
    void aConfirmWithNoReservationAtAllIsReportedTheSameWay() {
        LimitFixture fx = new LimitFixture("live");
        confirm(fx, null);
        fx.lcm.ok("/utilisations/reserve", "{\"reservationRef\":\"RSV-LATE\"}");

        fx.service.retryDue();

        assertThat(fx.outbox.get(1L).status()).isEqualTo("SENT");
        assertThat(fx.lcm.callsTo("/RSV-LATE/confirm")).hasSize(1);
    }

    @Test
    void aRepaymentForAnAccountLcmDoesNotTrackIsSkippedNotRetried() {
        LimitFixture fx = new LimitFixture("live");
        repay(fx, 41);
        fx.lcm.error("/repay", 422, "NO_POSITION", "No utilisation recorded for LOAN_ACCOUNT 7");

        fx.service.retryDue();

        assertThat(fx.outbox.get(1L).status()).isEqualTo("SKIPPED");
        assertThat(fx.outbox.get(1L).lastError()).contains("NO_POSITION");
    }

    @Test
    void aRefusalThatARetryWillNotChangeIsDeadAndDoesNotBlockTheAccountsNextCall() {
        LimitFixture fx = new LimitFixture("live");
        repay(fx, 41);
        close(fx);
        fx.lcm.error("/repay", 422, "REPAY_EXCEEDS_OUTSTANDING", "more than outstanding");

        fx.service.retryDue();
        assertThat(fx.outbox.get(1L).status()).isEqualTo("DEAD");
        assertThat(fx.lcm.callsTo("/close")).isEmpty(); // the close waited behind the repayment in the first sweep

        fx.service.retryDue();
        assertThat(fx.outbox.get(2L).status()).isEqualTo("SENT");
    }

    @Test
    void callsForOneAccountGoOutInOrderAndAStuckOneHoldsTheRestBack() {
        LimitFixture fx = new LimitFixture("live", false, 0, 5);
        confirm(fx, "RSV-1");
        repay(fx, 41);
        close(fx);
        fx.lcm.on("/RSV-1/confirm", new IOException("down"));

        fx.service.retryDue();
        fx.service.retryDue(); // not due yet (backoff)
        assertThat(fx.lcm.calls).hasSize(1);
        assertThat(fx.outbox.get(1L).status()).isEqualTo("PENDING");

        fx.lcm.calls.clear();
        fx.lcm.on("/RSV-1/confirm", new LimitTransport.Response(200, "{}"));
        fx.outbox.advanceMinutes(10);
        for (int i = 0; i < 3; i++) {
            fx.service.retryDue();
        }

        assertThat(fx.lcm.calls.stream().map(ScriptedTransport.Call::path).map(p -> p.substring(p.lastIndexOf('/') + 1)).toList())
                .containsExactly("confirm", "repay", "close");
        assertThat(fx.outbox.count("SENT")).isEqualTo(3L);
    }

    @Test
    void anUnreachableServiceIsRetriedWithBackoffAndGivenUpOnAfterTheMaximumAttempts() {
        LimitFixture fx = new LimitFixture("live", false, 0, 3);
        close(fx);
        fx.lcm.on("/close", new IOException("down"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            fx.outbox.advanceMinutes(120);
            assertThat(fx.service.retryDue()).isEqualTo(1);
        }

        LimitOutboxRepository.Row row = fx.outbox.get(1L);
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.lastError()).contains("gave up after 3 attempts");
    }

    @Test
    void aDeadRowCanBeRequeuedAndThenDelivered() {
        LimitFixture fx = new LimitFixture("live");
        repay(fx, 41);
        fx.lcm.error("/repay", 422, "REPAY_EXCEEDS_OUTSTANDING", "more than outstanding");
        fx.service.retryDue();
        assertThat(fx.outbox.get(1L).status()).isEqualTo("DEAD");
        assertThat(fx.outbox.requeue(1L)).isTrue();
        assertThat(fx.outbox.get(1L).status()).isEqualTo("PENDING");

        fx.lcm.on("/repay", new LimitTransport.Response(200, "{}")); // the second answer: the cause has been fixed
        fx.service.retryDue();

        assertThat(fx.outbox.get(1L).status()).isEqualTo("SENT");
        assertThat(fx.outbox.requeue(1L)).isFalse(); // only DEAD rows can be requeued
    }

    @Test
    void aRowThatAnotherNodeHasLeasedIsLeftAlone() {
        LimitFixture fx = new LimitFixture("live");
        close(fx);

        assertThat(fx.outbox.claimDue(10, 5)).hasSize(1);  // node A leases it
        assertThat(fx.outbox.claimDue(10, 5)).isEmpty();   // node B finds nothing due
        fx.outbox.advanceMinutes(6);
        assertThat(fx.outbox.claimDue(10, 5)).hasSize(1);  // lease expired (node A crashed): retried
    }

    @Test
    void backoffDoublesUpToAnHour() {
        assertThat(LimitOutboxService.backoffMinutes(1)).isEqualTo(2);
        assertThat(LimitOutboxService.backoffMinutes(3)).isEqualTo(8);
        assertThat(LimitOutboxService.backoffMinutes(9)).isEqualTo(60);
        assertThat(LimitOutboxService.backoffMinutes(50)).isEqualTo(60);
    }
}
