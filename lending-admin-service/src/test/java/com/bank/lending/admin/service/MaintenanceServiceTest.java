package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: clearing the loans and the limits together, in a safe order. */
class MaintenanceServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LendingMaintenanceClient lending = Mockito.mock(LendingMaintenanceClient.class);
    private final LcmClient lcm = Mockito.mock(LcmClient.class);
    private final MaintenanceService service = new MaintenanceService(lending, lcm);

    private JsonNode lcmStatus(boolean resetEnabled) {
        return JSON.createObjectNode().put("resetEnabled", resetEnabled);
    }

    private void lendingClearAnswers() {
        when(lending.clearData("admin")).thenReturn(JSON.createObjectNode().put("totalRows", 5)
                .put("businessDate", "2026-10-19").put("message", "Loans cleared."));
    }

    @Test
    void lcmSwitchedOffStopsTheWholeClearBeforeAnythingIsDeleted() {
        when(lcm.enabled()).thenReturn(true);
        when(lcm.get("/maintenance/status", Map.of())).thenReturn(lcmStatus(false));

        assertThatThrownBy(() -> service.clearData("admin"))
                .isInstanceOf(FeatureDisabledException.class).hasMessageContaining("LCM_RESET_ENABLED");
        verify(lending, never()).clearData(anyString());
        verify(lcm, never()).post(anyString(), anyMap(), any(), anyString());
    }

    @Test
    void lcmUnreachableStopsTheWholeClearBeforeAnythingIsDeleted() {
        when(lcm.enabled()).thenReturn(true);
        when(lcm.get("/maintenance/status", Map.of())).thenThrow(new UpstreamException(502, "Could not reach lending-lcm-service"));

        assertThatThrownBy(() -> service.clearData("admin")).isInstanceOf(UpstreamException.class);
        verify(lending, never()).clearData(anyString());
    }

    @Test
    void bothAreClearedLcmIsCheckedFirstAndTheAnswerCarriesBoth() {
        when(lcm.enabled()).thenReturn(true);
        when(lcm.get("/maintenance/status", Map.of())).thenReturn(lcmStatus(true));
        lendingClearAnswers();
        when(lcm.post(eq("/maintenance/clear-data"), anyMap(), eq(Map.of("confirm", "RESET")), eq("admin")))
                .thenReturn(JSON.createObjectNode().put("totalRows", 7).put("message", "Limits cleared."));

        JsonNode r = service.clearData("admin");

        InOrder order = inOrder(lcm, lending);
        order.verify(lcm).get("/maintenance/status", Map.of());
        order.verify(lending).clearData("admin");
        order.verify(lcm).post(eq("/maintenance/clear-data"), anyMap(), any(), eq("admin"));
        assertThat(r.path("totalRows").asInt()).isEqualTo(5);
        assertThat(r.path("businessDate").asText()).isEqualTo("2026-10-19");
        assertThat(r.path("lcm").path("totalRows").asInt()).isEqualTo(7);
        assertThat(r.path("lcm").path("configured").asBoolean()).isTrue();
        assertThat(r.path("message").asText()).contains("Loans cleared.").contains("Limits cleared.");
    }

    @Test
    void withoutLcmOnlyTheLoansAreClearedAndTheAnswerSaysSo() {
        when(lcm.enabled()).thenReturn(false);
        lendingClearAnswers();

        JsonNode r = service.clearData("admin");

        assertThat(r.path("lcm").path("configured").asBoolean(true)).isFalse();
        assertThat(r.path("message").asText()).contains("Limits and collateral were not cleared");
        verify(lcm, never()).post(anyString(), anyMap(), any(), anyString());
    }

    @Test
    void ifLcmFailsAfterTheLoansWereClearedTheErrorSaysWhatAlreadyHappened() {
        when(lcm.enabled()).thenReturn(true);
        when(lcm.get("/maintenance/status", Map.of())).thenReturn(lcmStatus(true));
        lendingClearAnswers();
        when(lcm.post(anyString(), anyMap(), any(), anyString())).thenThrow(new UpstreamException(500, "boom"));

        assertThatThrownBy(() -> service.clearData("admin"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getMessage()).contains("loans and products were cleared").contains("boom");
                });
    }

    @Test
    void statusStillLoadsWhenLcmCannotBeReached() {
        when(lending.status()).thenReturn(JSON.createObjectNode().put("resetEnabled", true));
        when(lcm.enabled()).thenReturn(true);
        when(lcm.get("/maintenance/status", Map.of())).thenThrow(new UpstreamException(502, "asleep"));

        JsonNode r = service.status();

        assertThat(r.path("resetEnabled").asBoolean()).isTrue();
        assertThat(r.path("lcm").path("configured").asBoolean()).isTrue();
        assertThat(r.path("lcm").path("reachable").asBoolean(true)).isFalse();
        assertThat(r.path("lcm").path("error").asText()).isEqualTo("asleep");
    }

    @Test
    void statusCarriesLcmSwitchAndCounts() {
        when(lending.status()).thenReturn(JSON.createObjectNode().put("resetEnabled", true));
        when(lcm.enabled()).thenReturn(true);
        com.fasterxml.jackson.databind.node.ObjectNode st = JSON.createObjectNode().put("resetEnabled", true);
        st.putObject("rowCounts").put("facility", 4);
        when(lcm.get("/maintenance/status", Map.of())).thenReturn(st);

        JsonNode r = service.status();

        assertThat(r.path("lcm").path("reachable").asBoolean()).isTrue();
        assertThat(r.path("lcm").path("resetEnabled").asBoolean()).isTrue();
        assertThat(r.path("lcm").path("rowCounts").path("facility").asInt()).isEqualTo(4);
    }

    @Test
    void statusWithoutLcmSaysNotConfigured() {
        when(lending.status()).thenReturn(JSON.createObjectNode().put("resetEnabled", true));
        when(lcm.enabled()).thenReturn(false);

        assertThat(service.status().path("lcm").path("configured").asBoolean(true)).isFalse();
    }
}
