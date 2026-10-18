package com.bank.lending.limits;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitClientTest {

    private static final String RESERVE = "/api/v1/lcm/utilisations/reserve";

    private LimitFixture fx() {
        return new LimitFixture("live");
    }

    @Test
    void reserveSendsTheBusinessFieldsAndTheIdempotencyKeyAndReadsTheReference() {
        LimitFixture fx = fx();
        fx.lcm.ok(RESERVE, "{\"reservationRef\":\"RSV-1\",\"status\":\"RESERVED\",\"wouldReject\":null}");

        LimitClient.ReservationResult r = fx.client.reserve("SANC-7", new BigDecimal("500000.00"), "INR", "ONLINE", 7L, true, 30,
                LocalDate.of(2026, 10, 10), "key-1");

        assertThat(r.reservationRef()).isEqualTo("RSV-1");
        assertThat(r.wouldReject()).isNull();
        ScriptedTransport.Call c = fx.lcm.calls.get(0);
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.key()).isEqualTo("key-1");
        assertThat(c.body()).contains("\"facilityRef\":\"SANC-7\"");
        assertThat(c.body()).contains("\"referenceType\":\"LOAN_ACCOUNT\"");
        assertThat(c.body()).contains("\"referenceId\":\"7\"");
        assertThat(c.body()).contains("\"enforce\":true");
        assertThat(c.body()).contains("\"businessDate\":\"2026-10-10\"");
    }

    @Test
    void aServerErrorIsRetriedWithTheSameKeyAndThenSucceeds() {
        LimitFixture fx = fx();
        fx.lcm.on(RESERVE, new LimitTransport.Response(503, "")).ok(RESERVE, "{\"reservationRef\":\"RSV-2\"}");

        LimitClient.ReservationResult r = fx.client.reserve("SANC-7", BigDecimal.TEN, "INR", "ONLINE", 7L, true, 30, null, "key-2");

        assertThat(r.reservationRef()).isEqualTo("RSV-2");
        assertThat(fx.lcm.calls).hasSize(2);
        assertThat(fx.lcm.calls.get(1).key()).isEqualTo("key-2");
    }

    @Test
    void whenEveryAttemptFailsTheCallerSeesUnavailableAfterRetriesPlusOneTries() {
        LimitFixture fx = fx();
        fx.lcm.on(RESERVE, new IOException("connection refused"));

        assertThatThrownBy(() -> fx.client.reserve("SANC-7", BigDecimal.TEN, "INR", "ONLINE", 7L, true, 30, null, "k"))
                .isInstanceOf(LimitUnavailableException.class).hasMessageContaining("connection refused");
        assertThat(fx.lcm.calls).hasSize(3);
    }

    @Test
    void aBusinessRefusalIsNotRetriedAndCarriesLcmsCode() {
        LimitFixture fx = fx();
        fx.lcm.error(RESERVE, 422, "LIMIT_EXCEEDED", "Available 100.00 is less than 500.00");

        assertThatThrownBy(() -> fx.client.reserve("SANC-7", BigDecimal.TEN, "INR", "ONLINE", 7L, true, 30, null, "k"))
                .isInstanceOf(LimitRejectedException.class).hasMessageContaining("Available 100.00");
        assertThat(fx.lcm.calls).hasSize(1);
        try {
            fx.client.reserve("SANC-7", BigDecimal.TEN, "INR", "ONLINE", 7L, true, 30, null, "k2");
        } catch (LimitRejectedException e) {
            assertThat(e.code()).isEqualTo("LIMIT_EXCEEDED");
            assertThat(e.httpStatus()).isEqualTo(422);
        }
    }

    @Test
    void aRejectedServiceCredentialIsReportedAsUnavailableNotAsALimitAnswer() {
        LimitFixture fx = fx();
        fx.lcm.on(RESERVE, new LimitTransport.Response(401, ""));

        assertThatThrownBy(() -> fx.client.reserve("SANC-7", BigDecimal.TEN, "INR", "ONLINE", 7L, true, 30, null, "k"))
                .isInstanceOf(LimitUnavailableException.class).hasMessageContaining("401");
        assertThat(fx.lcm.calls).hasSize(1);
    }

    @Test
    void checkReadsAllowedAndTheRejectionCode() {
        LimitFixture fx = fx();
        fx.lcm.ok("/check", "{\"allowed\":false,\"rejectionCode\":\"FACILITY_EXPIRED\",\"message\":\"expired\"}");

        LimitClient.CheckResult r = fx.client.check("SANC-7", BigDecimal.TEN, "INR", "ONLINE", null);

        assertThat(r.allowed()).isFalse();
        assertThat(r.code()).isEqualTo("FACILITY_EXPIRED");
        assertThat(fx.lcm.calls.get(0).key()).isNull();
    }

    @Test
    void repayAndCloseAddressTheLoanAccountReference() {
        LimitFixture fx = fx();

        fx.client.repay(7L, new BigDecimal("25000.00"), "r-1");
        fx.client.close(7L, "FORECLOSED", "c-1");

        assertThat(fx.lcm.calls.get(0).path()).isEqualTo("/api/v1/lcm/utilisations/repay");
        assertThat(fx.lcm.calls.get(0).body()).contains("\"amount\":25000.00");
        assertThat(fx.lcm.calls.get(1).path()).isEqualTo("/api/v1/lcm/utilisations/close");
        assertThat(fx.lcm.calls.get(1).body()).contains("\"reason\":\"FORECLOSED\"");
    }

    @Test
    void collateralCoverIsAGetOnTheFacilityAndReadsCoveredValueAndItemCount() {
        LimitFixture fx = fx();
        fx.lcm.ok("/collateral-cover", "{\"covered\":true,\"coveredValue\":3375000.00,\"items\":[{\"collateralRef\":\"MORT-1\"},{\"collateralRef\":\"MORT-2\"}]}");

        LimitClient.CoverResult r = fx.client.collateralCover("SANC-7");

        assertThat(r.covered()).isTrue();
        assertThat(r.coveredValue()).isEqualByComparingTo("3375000.00");
        assertThat(r.items()).isEqualTo(2);
        ScriptedTransport.Call c = fx.lcm.calls.get(0);
        assertThat(c.method()).isEqualTo("GET");
        assertThat(c.path()).isEqualTo("/api/v1/lcm/facilities/SANC-7/collateral-cover");
        assertThat(c.key()).isNull();
        assertThat(c.body()).isNull();
    }

    @Test
    void collateralCoverOfAnEmptyAnswerIsNotCovered() {
        LimitFixture fx = fx();
        fx.lcm.ok("/collateral-cover", "{}");

        LimitClient.CoverResult r = fx.client.collateralCover("SANC-7");

        assertThat(r.covered()).isFalse();
        assertThat(r.coveredValue()).isEqualByComparingTo("0");
        assertThat(r.items()).isZero();
    }
}
