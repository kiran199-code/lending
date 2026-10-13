package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingAccountClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Spring-free: the console rejects an empty or malformed form before it calls lending-service. */
class AccountOperationsControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LendingAccountClient client = Mockito.mock(LendingAccountClient.class);
    private final AccountOperationsController controller = new AccountOperationsController(client);
    private final Principal admin = () -> "admin";

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createNeedsTheFieldsLendingServiceRequires() {
        assertThatThrownBy(() -> controller.create(json("{\"customerId\":1,\"sanctionRefId\":2,\"productCode\":\"PL01\",\"tenureMonths\":12}"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sanctionedAmount");
        assertThatThrownBy(() -> controller.create(json("{\"customerId\":\"abc\",\"sanctionRefId\":2,\"productCode\":\"PL01\",\"sanctionedAmount\":1000,\"tenureMonths\":12}"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customerId");
        verify(client, never()).createAccount(any(), anyString());
    }

    @Test
    void createIsForwardedWithTheSignedInUser() {
        JsonNode body = json("{\"customerId\":1,\"sanctionRefId\":2,\"productCode\":\"PL01\",\"sanctionedAmount\":100000,\"tenureMonths\":12}");
        controller.create(body, admin);
        verify(client).createAccount(body, "admin");
    }

    @Test
    void disburseNeedsAnAmountAndAFirstDueDate() {
        assertThatThrownBy(() -> controller.disburse(1L, json("{\"disbursedAmount\":0,\"firstEmiDueDate\":\"2026-12-05\"}"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> controller.disburse(1L, json("{\"disbursedAmount\":500}"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstEmiDueDate");
        assertThatThrownBy(() -> controller.disburse(1L, json("{\"disbursedAmount\":500,\"firstEmiDueDate\":\"05/12/2026\"}"), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yyyy-MM-dd");
        verify(client, never()).disburse(anyLong(), any(), anyString());
    }

    @Test
    void paymentsNeedDateAmountAndChannel() {
        assertThatThrownBy(() -> controller.payInstalment(1L, json("{\"valueDate\":\"2026-11-05\",\"amount\":100}"), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel");
        assertThatThrownBy(() -> controller.partPayment(1L, json("{\"valueDate\":\"2026-11-05\",\"amount\":-5,\"channel\":\"MANUAL\"}"), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
        verify(client, never()).payInstalment(anyLong(), any(), anyString(), anyString());
        verify(client, never()).partPayment(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void aPaymentWithoutAnIdempotencyKeyGetsOneAndTheGivenKeyIsKept() {
        JsonNode body = json("{\"valueDate\":\"2026-11-05\",\"amount\":100,\"channel\":\"MANUAL\"}");

        controller.payInstalment(7L, body, null, admin);
        ArgumentCaptor<String> generated = ArgumentCaptor.forClass(String.class);
        verify(client).payInstalment(eq(7L), eq(body), eq("admin"), generated.capture());
        assertThat(generated.getValue()).isNotBlank();

        controller.partPayment(7L, body, " key-1 ", admin);
        verify(client).partPayment(7L, body, "admin", "key-1");
    }

    @Test
    void foreclosureNeedsADateAndAChannel() {
        assertThatThrownBy(() -> controller.foreclose(1L, json("{\"channel\":\"MANUAL\"}"), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("foreclosureDate");
        assertThatThrownBy(() -> controller.foreclose(1L, json("{\"foreclosureDate\":\"2026-11-05\",\"channel\":\"CASH\"}"), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel");
    }

    @Test
    void bulkCallsNeedBetweenOneAndTwoHundredItems() {
        assertThatThrownBy(() -> controller.bulkCreate(json("{\"accounts\":[]}"), admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.bulkDisburse(json("{}"), admin)).isInstanceOf(IllegalArgumentException.class);
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append("{\"accountId\":").append(i + 1).append('}');
        }
        many.append("]}");
        assertThatThrownBy(() -> controller.bulkDisburse(json(many.toString()), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
        verify(client, never()).bulkCreate(any(), anyString());
        verify(client, never()).bulkDisburse(any(), anyString());
    }
}
