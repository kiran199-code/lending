package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingAccountingClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Spring-free: the accounting screens reject a malformed filter before calling lending-service, and forward the rest. */
class AccountingControllerTest {

    private final LendingAccountingClient client = Mockito.mock(LendingAccountingClient.class);
    private final AccountingController controller = new AccountingController(client);
    private final Principal admin = () -> "admin";

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureQuery(String path) {
        ArgumentCaptor<Map<String, Object>> q = ArgumentCaptor.forClass(Map.class);
        verify(client).get(eq(path), q.capture());
        return q.getValue();
    }

    @Test
    void journalFiltersAreForwardedAndThePageSizeIsCapped() {
        controller.journals(7L, "EMI_PAYMENT", null, "2026-10-01", "2026-10-31", "BLR", 10_000, -5);

        Map<String, Object> q = captureQuery("/journals");
        assertThat(q).containsEntry("accountId", 7L).containsEntry("eventCode", "EMI_PAYMENT")
                .containsEntry("from", "2026-10-01").containsEntry("to", "2026-10-31").containsEntry("branchCode", "BLR")
                .containsEntry("limit", AccountingController.MAX_PAGE).containsEntry("offset", 0);
        assertThat(q.get("journalNo")).isNull();
    }

    @Test
    void aMalformedDateOrCodeNeverReachesLendingService() {
        assertThatThrownBy(() -> controller.journals(null, null, null, "01/10/2026", null, null, 50, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> controller.journals(null, "A B{x}", null, null, null, null, 50, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventCode");
        assertThatThrownBy(() -> controller.trialBalance("tomorrow", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.trialBalance(null, null, "BLR; DROP"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("branchCode");
        verify(client, never()).get(anyString(), anyMap());
    }

    @Test
    void currenciesAndBranchesAreReadFromTheReferenceApi() {
        controller.currencies();
        controller.branches();
        verify(client).reference("/currencies");
        verify(client).reference("/branches");
    }

    @Test
    void exceptionStatusMustBeOneOfTheKnownOnes() {
        assertThatThrownBy(() -> controller.exceptions("DONE", 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OPEN, RESOLVED, WAIVED or ALL");
        controller.exceptions("ALL", 100, 0);
        assertThat(captureQuery("/exceptions")).containsEntry("status", "ALL");
    }

    @Test
    void waivingNeedsAReasonAndIsDoneAsTheSignedInUser() {
        assertThatThrownBy(() -> controller.waive(5L, Map.of("reason", "  "), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        verify(client, never()).post(anyString(), anyMap(), any(), anyString());

        controller.waive(5L, Map.of("reason", " duplicate of journal AJ-1 "), admin);
        verify(client).post(eq("/exceptions/5/waive"), eq(Map.of()), eq(Map.of("reason", "duplicate of journal AJ-1")), eq("admin"));
    }

    @Test
    void retryAndReconciliationRunAreDoneAsTheSignedInUser() {
        controller.retry(9L, admin);
        controller.runReconciliation(admin);
        verify(client).post("/exceptions/9/retry", Map.of(), null, "admin");
        verify(client).post("/reconciliation/run", Map.of(), null, "admin");
    }
}
