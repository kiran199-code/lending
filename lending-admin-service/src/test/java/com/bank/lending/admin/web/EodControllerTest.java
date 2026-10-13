package com.bank.lending.admin.web;

import com.bank.lending.admin.common.UpstreamException;
import com.bank.lending.admin.service.BatchConfigClient;
import com.bank.lending.admin.service.BusinessDateSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the console checks the date on screen against the batch service's before it starts end of day. */
class EodControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BatchConfigClient batch = Mockito.mock(BatchConfigClient.class);
    private final BusinessDateSource dates = Mockito.mock(BusinessDateSource.class);
    private final EodController controller = new EodController(batch, dates);

    private JsonNode batchStatus(String businessDate) throws Exception {
        return JSON.readTree("{\"running\":false,\"businessDate\":{\"businessDate\":\"" + businessDate
                + "\",\"status\":\"OPEN\"},\"progress\":{\"state\":\"IDLE\"},\"lastRun\":null}");
    }

    @Test
    void startingWithoutTheDateOnScreenIsRefused() {
        when(batch.enabled()).thenReturn(true);
        assertThatThrownBy(() -> controller.run(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.run(Map.of("expectedBusinessDate", "02/11/2026"))).isInstanceOf(IllegalArgumentException.class);
        verify(batch, never()).eodRun();
    }

    @Test
    void startingWhenTheBusinessDateHasMovedOnIsRefusedWith409() throws Exception {
        when(batch.eodStatus()).thenReturn(batchStatus("2026-11-03"));

        assertThatThrownBy(() -> controller.run(Map.of("expectedBusinessDate", "2026-11-02")))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("2026-11-03");
                });
        verify(batch, never()).eodRun();
    }

    @Test
    void startingWithTheCurrentDateIsForwarded() throws Exception {
        when(batch.eodStatus()).thenReturn(batchStatus("2026-11-02"));
        when(batch.eodRun()).thenReturn(JSON.readTree("{\"started\":true}"));

        JsonNode r = controller.run(Map.of("expectedBusinessDate", "2026-11-02"));

        assertThat(r.path("started").asBoolean()).isTrue();
        verify(batch).eodRun();
    }

    @Test
    void statusCarriesTheBatchProgressAndTheDatabaseDate() throws Exception {
        when(batch.enabled()).thenReturn(true);
        when(batch.eodStatus()).thenReturn(batchStatus("2026-11-02"));
        when(dates.today()).thenReturn(LocalDate.of(2026, 11, 2));
        when(dates.status()).thenReturn("OPEN");

        JsonNode s = controller.status();

        assertThat(s.path("reachable").asBoolean()).isTrue();
        assertThat(s.path("progress").path("state").asText()).isEqualTo("IDLE");
        assertThat(s.path("businessDate").path("businessDate").asText()).isEqualTo("2026-11-02");
        assertThat(s.path("databaseBusinessDate").asText()).isEqualTo("2026-11-02");
    }

    @Test
    void statusStillShowsTheDatabaseDateWhenTheBatchServiceCannotBeReached() {
        when(batch.enabled()).thenReturn(true);
        when(batch.eodStatus()).thenThrow(new UpstreamException(502, "Could not reach lending-batch-service"));
        when(dates.today()).thenReturn(LocalDate.of(2026, 11, 2));
        when(dates.status()).thenReturn("EOD_RUNNING");

        JsonNode s = controller.status();

        assertThat(s.path("reachable").asBoolean()).isFalse();
        assertThat(s.path("error").asText()).contains("Could not reach");
        assertThat(s.path("databaseBusinessDate").asText()).isEqualTo("2026-11-02");
        assertThat(s.path("databaseStatus").asText()).isEqualTo("EOD_RUNNING");
    }

    @Test
    void statusExplainsWhenTheBatchServiceIsNotConfigured() {
        when(batch.enabled()).thenReturn(false);
        when(dates.today()).thenReturn(LocalDate.of(2026, 11, 2));

        JsonNode s = controller.status();

        assertThat(s.path("configured").asBoolean()).isFalse();
        assertThat(s.path("error").asText()).contains("BATCH_SERVICE_URL");
        verify(batch, never()).eodStatus();
    }
}
