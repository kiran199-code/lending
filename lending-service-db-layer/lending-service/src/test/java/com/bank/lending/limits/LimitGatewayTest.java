package com.bank.lending.limits;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitGatewayTest {

    private static final String RESERVE = "/api/v1/lcm/utilisations/reserve";
    private static final String CHECK = "/api/v1/lcm/utilisations/check";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    private static final BigDecimal AMOUNT = new BigDecimal("500000.00");

    // ------------------------------------------------------------------------------------------ mode off

    @Test
    void offMeansNoCallAndNoRowWhateverIsAskedOf() {
        LimitFixture fx = new LimitFixture("off");

        fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY);
        LimitGateway.Ticket t = fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY);
        fx.gateway.disbursed(LimitFixture.LINKED, t, AMOUNT, DAY);
        fx.gateway.principalRepaid(LimitFixture.LINKED, 1L, AMOUNT, DAY);
        fx.gateway.accountClosed(LimitFixture.LINKED, "FULLY_PAID", DAY);

        assertThat(t).isEqualTo(LimitGateway.Ticket.NONE);
        assertThat(fx.lcm.calls).isEmpty();
        assertThat(fx.outbox.rows).isEmpty();
    }

    @Test
    void anAccountWithoutAFacilityIsNeverLimitControlledInAnyMode() {
        for (String mode : new String[]{"shadow", "live"}) {
            LimitFixture fx = new LimitFixture(mode);

            LimitGateway.Ticket t = fx.gateway.reserveForDisbursement(LimitFixture.UNLINKED, AMOUNT, DAY);
            fx.gateway.disbursed(LimitFixture.UNLINKED, t, AMOUNT, DAY);
            fx.gateway.principalRepaid(LimitFixture.UNLINKED, 1L, AMOUNT, DAY);
            fx.gateway.accountClosed(LimitFixture.UNLINKED, "FULLY_PAID", DAY);
            fx.gateway.checkOnCreate(null, AMOUNT, "INR", "ONLINE", DAY);

            assertThat(fx.lcm.calls).isEmpty();
            assertThat(fx.outbox.rows).isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------- create check

    @Test
    void liveStopsAnAccountOnAFacilityThatCannotBeUsed() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(CHECK, "{\"allowed\":false,\"rejectionCode\":\"FACILITY_EXPIRED\",\"message\":\"Facility SANC-7 expired\"}");

        assertThatThrownBy(() -> fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY))
                .isInstanceOf(LimitRejectedException.class).hasMessageContaining("expired");
    }

    @Test
    void liveStopsAFacilityOfAnotherCustomerAndSendsTheCustomerId() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(CHECK, "{\"allowed\":false,\"rejectionCode\":\"CUSTOMER_MISMATCH\",\"message\":\"belongs to customer 5\"}");

        assertThatThrownBy(() -> fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY, 9L))
                .isInstanceOf(LimitRejectedException.class).hasMessageContaining("customer");
        assertThat(fx.lcm.callsTo(CHECK).get(0).body()).contains("\"customerId\":9");
    }

    @Test
    void shadowOnlyLogsAFacilityOfAnotherCustomer() {
        LimitFixture fx = new LimitFixture("shadow");
        fx.lcm.ok(CHECK, "{\"allowed\":false,\"rejectionCode\":\"CUSTOMER_MISMATCH\",\"message\":\"belongs to customer 5\"}");

        fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY, 9L); // does not throw
    }

    @Test
    void anAmountShortfallAtCreationIsOnlyAdvisory() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(CHECK, "{\"allowed\":false,\"rejectionCode\":\"LIMIT_EXCEEDED\",\"message\":\"not enough yet\"}");

        fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY); // does not throw
    }

    @Test
    void liveStopsAnUnknownFacilityReference() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.error(CHECK, 404, "NOT_FOUND", "Facility not found: SANC-7");

        assertThatThrownBy(() -> fx.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY))
                .isInstanceOf(LimitRejectedException.class);
    }

    @Test
    void shadowNeverStopsAnAccountEvenOnARefusalOrAnOutage() {
        LimitFixture refused = new LimitFixture("shadow");
        refused.lcm.ok(CHECK, "{\"allowed\":false,\"rejectionCode\":\"FACILITY_EXPIRED\",\"message\":\"expired\"}");
        refused.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY);

        LimitFixture down = new LimitFixture("shadow", false, 0, 3);
        down.lcm.on(CHECK, new java.io.IOException("down"));
        down.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY);
    }

    @Test
    void liveFailsClosedOnAnOutageUnlessFailOpenIsSet() {
        LimitFixture closed = new LimitFixture("live", false, 0, 3);
        closed.lcm.on(CHECK, new java.io.IOException("down"));
        assertThatThrownBy(() -> closed.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY))
                .isInstanceOf(LimitUnavailableException.class);

        LimitFixture open = new LimitFixture("live", true, 0, 3);
        open.lcm.on(CHECK, new java.io.IOException("down"));
        open.gateway.checkOnCreate("SANC-7", AMOUNT, "INR", "ONLINE", DAY); // does not throw
    }

    // ------------------------------------------------------------------------------------ secured loans

    /** The LCM-style code of the refusal {@code body} throws; fails the test if it throws something else or nothing. */
    private static String refusalCode(Runnable body) {
        try {
            body.run();
        } catch (LimitRejectedException e) {
            return e.code();
        }
        throw new AssertionError("expected a LimitRejectedException");
    }

    private static final String COVER = "/api/v1/lcm/facilities/SANC-7/collateral-cover";

    @Test
    void aSecuredLoanIsRefusedUnlessLimitsAreLive() {
        for (String mode : new String[]{"off", "shadow"}) {
            LimitFixture fx = new LimitFixture(mode);

            assertThatThrownBy(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))
                    .isInstanceOf(LimitRejectedException.class)
                    .hasMessageContaining("lending.lcm.mode");
            assertThat(refusalCode(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))).isEqualTo("COLLATERAL_NOT_CHECKED");
            assertThat(fx.lcm.calls).isEmpty();
        }
    }

    @Test
    void aSecuredLoanWithoutAFacilityIsRefusedWithoutAskingLcm() {
        LimitFixture fx = new LimitFixture("live");

        assertThat(refusalCode(() -> fx.gateway.requireCollateral(LimitFixture.UNLINKED))).isEqualTo("NO_FACILITY");
        assertThat(fx.lcm.calls).isEmpty();
    }

    @Test
    void aSecuredLoanIsRefusedWhenLcmHoldsNoActiveCollateralForItsFacility() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(COVER, "{\"facilityRef\":\"SANC-7\",\"customerId\":9,\"covered\":false,\"coveredValue\":0,\"items\":[]}");

        assertThatThrownBy(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))
                .isInstanceOf(LimitRejectedException.class)
                .hasMessageContaining("SANC-7");
        assertThat(refusalCode(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))).isEqualTo("COLLATERAL_NOT_COVERED");
        assertThat(fx.lcm.callsTo(COVER).get(0).method()).isEqualTo("GET");
    }

    @Test
    void aSecuredLoanPassesWhenLcmReportsActiveCollateralAllocatedToTheFacility() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(COVER, "{\"facilityRef\":\"SANC-7\",\"customerId\":9,\"covered\":true,\"coveredValue\":5625000.00,"
                + "\"items\":[{\"collateralRef\":\"MORT-1\",\"allocationPct\":100}]}");

        fx.gateway.requireCollateral(LimitFixture.LINKED); // does not throw
    }

    @Test
    void aSecuredLoanIsRefusedWhenLcmCannotBeAskedEvenIfFailOpenIsSet() {
        LimitFixture fx = new LimitFixture("live", true, 0, 3);
        fx.lcm.on(COVER, new java.io.IOException("down"));

        assertThatThrownBy(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))
                .isInstanceOf(LimitUnavailableException.class);
    }

    @Test
    void anUnknownFacilityIsReportedByLcmAsARefusal() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.error(COVER, 404, "NOT_FOUND", "Facility not found: SANC-7");

        assertThatThrownBy(() -> fx.gateway.requireCollateral(LimitFixture.LINKED))
                .isInstanceOf(LimitRejectedException.class).hasMessageContaining("not found");
    }

    // ----------------------------------------------------------------------------------------- disbursement

    @Test
    void liveReservesWithEnforceAndRefusalStopsTheDisbursementBeforeAnythingIsQueued() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.error(RESERVE, 422, "LIMIT_EXCEEDED", "Available 100.00 is less than 500000.00");

        assertThatThrownBy(() -> fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY))
                .isInstanceOf(LimitRejectedException.class).hasMessageContaining("Available 100.00");
        assertThat(fx.lcm.calls.get(0).body()).contains("\"enforce\":true");
        assertThat(fx.outbox.rows).isEmpty();
    }

    @Test
    void shadowReservesWithoutEnforceAndIgnoresARefusal() {
        LimitFixture fx = new LimitFixture("shadow");
        fx.lcm.error(RESERVE, 422, "LIMIT_EXCEEDED", "too much");

        LimitGateway.Ticket t = fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY);

        assertThat(t).isEqualTo(LimitGateway.Ticket.NONE);
        assertThat(fx.lcm.calls.get(0).body()).contains("\"enforce\":false");
    }

    @Test
    void liveFailsClosedWhenLcmIsDownAndNothingHasBeenReserved() {
        LimitFixture fx = new LimitFixture("live", false, 0, 3);
        fx.lcm.on(RESERVE, new java.io.IOException("down"));

        assertThatThrownBy(() -> fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY))
                .isInstanceOf(LimitUnavailableException.class);
        assertThat(fx.outbox.rows).isEmpty();
    }

    @Test
    void failOpenLetsTheLoanThroughAndStillQueuesTheDrawingForLater() {
        LimitFixture fx = new LimitFixture("live", true, 0, 3);
        fx.lcm.on(RESERVE, new java.io.IOException("down"));
        fx.lcm.on("/confirm", new java.io.IOException("still down"));

        LimitGateway.Ticket t = fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY);
        fx.inTransaction(true, () -> fx.gateway.disbursed(LimitFixture.LINKED, t, AMOUNT, DAY));

        assertThat(t.reservationRef()).isNull();
        assertThat(t.track()).isTrue();
        LimitOutboxRepository.Row row = fx.outbox.get(1L);
        assertThat(row.operation()).isEqualTo("CONFIRM_DISBURSEMENT");
        assertThat(row.reservationRef()).isNull();
        assertThat(row.status()).isEqualTo("PENDING");
    }

    @Test
    void theConfirmationIsQueuedWithTheLoanAndSentOnlyAfterCommit() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(RESERVE, "{\"reservationRef\":\"RSV-9\"}");
        LimitGateway.Ticket[] ticket = new LimitGateway.Ticket[1];

        fx.inTransaction(true, () -> {
            ticket[0] = fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY);
            fx.gateway.disbursed(LimitFixture.LINKED, ticket[0], AMOUNT, DAY);
            assertThat(fx.outbox.rows).hasSize(1);
            assertThat(fx.lcm.callsTo("/confirm")).isEmpty(); // not before the commit
        });

        assertThat(ticket[0].reservationRef()).isEqualTo("RSV-9");
        assertThat(fx.lcm.callsTo("/RSV-9/confirm")).hasSize(1);
        assertThat(fx.lcm.callsTo("/RSV-9/confirm").get(0).key()).isEqualTo("lend-confirm-7");
        assertThat(fx.outbox.get(1L).status()).isEqualTo("SENT");
        assertThat(fx.lcm.callsTo("/release")).isEmpty();
    }

    @Test
    void aRolledBackDisbursementReleasesTheReservationAndConfirmsNothing() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.ok(RESERVE, "{\"reservationRef\":\"RSV-10\"}");

        assertThatThrownBy(() -> fx.inTransaction(true, () -> {
            LimitGateway.Ticket t = fx.gateway.reserveForDisbursement(LimitFixture.LINKED, AMOUNT, DAY);
            fx.gateway.disbursed(LimitFixture.LINKED, t, AMOUNT, DAY);
            throw new IllegalStateException("schedule generation failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(fx.lcm.callsTo("/RSV-10/release")).hasSize(1);
        assertThat(fx.lcm.callsTo("/confirm")).isEmpty();
    }

    // ----------------------------------------------------------------------------------- repay / close

    @Test
    void principalRepaymentsAreQueuedOncePerPaymentAndZeroIsIgnored() {
        LimitFixture fx = new LimitFixture("live");
        fx.lcm.on("/repay", new java.io.IOException("down")); // keep them queued so we can look

        fx.inTransaction(true, () -> {
            fx.gateway.principalRepaid(LimitFixture.LINKED, 41L, new BigDecimal("12000.00"), DAY);
            fx.gateway.principalRepaid(LimitFixture.LINKED, 41L, new BigDecimal("12000.00"), DAY); // same payment replayed
            fx.gateway.principalRepaid(LimitFixture.LINKED, 42L, BigDecimal.ZERO, DAY);              // interest-only payment
        });

        assertThat(fx.outbox.rows).hasSize(1);
        assertThat(fx.outbox.rows.get(0).key).isEqualTo("lend-repay-41");
        assertThat(fx.outbox.rows.get(0).operation).isEqualTo("REPAY");
    }

    @Test
    void closingIsQueuedOncePerAccount() {
        LimitFixture fx = new LimitFixture("shadow");

        fx.inTransaction(true, () -> {
            fx.gateway.accountClosed(LimitFixture.LINKED, "FULLY_PAID", DAY);
            fx.gateway.accountClosed(LimitFixture.LINKED, "FULLY_PAID", DAY);
        });

        assertThat(fx.outbox.rows).hasSize(1);
        assertThat(fx.lcm.callsTo("/close")).hasSize(1);
        assertThat(fx.lcm.callsTo("/close").get(0).key()).isEqualTo("lend-close-7");
    }

    @Test
    void nothingIsSentForARepaymentThatRolledBack() {
        LimitFixture fx = new LimitFixture("live");

        fx.inTransaction(false, () -> fx.gateway.principalRepaid(LimitFixture.LINKED, 50L, AMOUNT, DAY));

        assertThat(fx.lcm.calls).isEmpty(); // (the queue row itself is rolled back by the database in the real thing)
    }
}
