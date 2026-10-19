package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingMaintenanceClient;
import com.bank.lending.admin.service.MaintenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the portal asks for the confirmation word before it calls anything. */
class MaintenanceControllerTest {

    private final LendingMaintenanceClient client = Mockito.mock(LendingMaintenanceClient.class);
    private final MaintenanceService service = Mockito.mock(MaintenanceService.class);
    private final MaintenanceController controller = new MaintenanceController(client, service);
    private final Principal admin = () -> "admin";

    @Test
    void clearWithoutTheConfirmationWordIsRefusedBeforeCallingAnyService() {
        assertThatThrownBy(() -> controller.clearData(Map.of("confirm", "reset"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RESET");
        assertThatThrownBy(() -> controller.clearData(null, admin)).isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).clearData(anyString());
        verify(client, never()).clearData(anyString());
    }

    @Test
    void clearWithTheConfirmationWordIsForwardedWithTheSignedInUser() {
        when(service.clearData("admin")).thenReturn(new ObjectMapper().createObjectNode().put("totalRows", 3));

        controller.clearData(Map.of("confirm", "RESET"), admin);

        verify(service).clearData("admin");
    }
}
