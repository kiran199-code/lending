package com.bank.lending.admin.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {

    private static final List<String> ALL_ROLES = List.of("BATCH_OPS", "LOAN_OPS", "LIMITS_OPS");
    private static final String[][] INQUIRIES = {
            {"GET", "/api/admin/me"}, {"GET", "/api/admin/business-date"}, {"GET", "/api/admin/dashboard"}, {"GET", "/api/admin/accounts"},
            {"GET", "/api/admin/accounts/27"}, {"GET", "/api/admin/accounts/27/schedule"}, {"GET", "/api/admin/accounts/27/statement"},
            {"GET", "/api/admin/accounts/by-number/LN0001"}, {"GET", "/api/admin/upcoming-payments"}, {"GET", "/api/admin/overdue"},
            {"GET", "/api/admin/overdue/buckets"}, {"GET", "/api/admin/payments"}, {"GET", "/api/admin/accruals"},
            {"GET", "/api/admin/accruals/summary"}, {"GET", "/api/admin/ledger-outbox"}};

    @Test
    void everyOperationsUserGetsEveryInquiry() {
        for (String role : ALL_ROLES) {
            for (String[] call : INQUIRIES) {
                assertTrue(AccessPolicy.allowed(call[0], call[1], List.of(role)), role + " " + call[1]);
            }
        }
    }

    @Test
    void adminCanDoEverythingIncludingUnlistedPaths() {
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", List.of("ADMIN")));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/accounting/reconciliation/run", List.of("ADMIN")));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/something/new", List.of("ADMIN")));
    }

    @Test
    void batchUserRunsBatchEodAndConfigurationOnly() {
        List<String> r = List.of("BATCH_OPS");
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/batch", r));
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/eod/status", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/eod/run", r));
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/config", r));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/config/batch-jobs/ACCRUAL", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/config/batch-jobs/apply", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/operations/accounts", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/limits/facilities", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/products/HL01", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", r));
    }

    @Test
    void otherUsersCannotRunBatchOrEditConfiguration() {
        for (String role : List.of("LOAN_OPS", "LIMITS_OPS")) {
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/eod/run", List.of(role)), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/batch", List.of(role)), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/config", List.of(role)), role);
            assertFalse(AccessPolicy.allowed("PUT", "/api/admin/config/batch-jobs/X", List.of(role)), role);
        }
    }

    @Test
    void loanUserCreatesDisbursesPaysAndMaintainsProducts() {
        List<String> r = List.of("LOAN_OPS");
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/operations/accounts", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/operations/accounts/27/disburse", r));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/operations/accounts/27/limit-facility", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/operations/accounts/27/payments/foreclosure", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/operations/accounts/bulk/disburse", r));
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/products", r));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/products/HL01", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/limits/collateral", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/limits/changes/5/approve", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/eod/run", r));
    }

    @Test
    void limitsUserChangesFacilitiesAndCollateralButNotLoans() {
        List<String> r = List.of("LIMITS_OPS");
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/limits/facilities", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/limits/changes/5/approve", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/limits/collateral/C1/activate", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/limits/collateral/C1/allocations", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/limits/monitoring/sweep", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/operations/accounts/27/disburse", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/operations/accounts/27/limit-facility", r));
        assertFalse(AccessPolicy.allowed("GET", "/api/admin/products", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/products/HL01", r));
    }

    @Test
    void limitsAndReferenceDataAreReadableByEveryone() {
        for (String role : ALL_ROLES) {
            assertTrue(AccessPolicy.allowed("GET", "/api/admin/limits/facilities/F1/collateral-cover", List.of(role)), role);
            assertTrue(AccessPolicy.allowed("GET", "/api/admin/limits/collateral/C1", List.of(role)), role);
            assertTrue(AccessPolicy.allowed("GET", "/api/admin/accounting/branches", List.of(role)), role);
            assertTrue(AccessPolicy.allowed("GET", "/api/admin/accounting/currencies", List.of(role)), role);
        }
    }

    @Test
    void accountingAndMaintenanceAreAdminOnly() {
        for (String role : ALL_ROLES) {
            List<String> r = List.of(role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/accounting/journals", r), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/accounting/trial-balance", r), role);
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/accounting/exceptions/1/waive", r), role);
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/accounting/reconciliation/run", r), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/maintenance/status", r), role);
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/maintenance/base-products", r), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/something/new", r), role);
        }
    }

    @Test
    void aWriteIsNeverOpenJustBecauseTheReadIs() {
        for (String role : ALL_ROLES) {
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/accounts/27", List.of(role)), role);
            assertFalse(AccessPolicy.allowed("DELETE", "/api/admin/payments", List.of(role)), role);
        }
    }

    @Test
    void usersExistOnlyWhenTheirPasswordIsSet() {
        var users = AdminUsers.build("admin", "pw", "batch-ops", "", "loan-ops", "x", "limits-ops", " ");
        assertEquals(2, users.size());
        assertEquals("admin", users.get(0).username());
        assertEquals("ADMIN", users.get(0).role());
        assertEquals("loan-ops", users.get(1).username());
        assertEquals("LOAN_OPS", users.get(1).role());
        assertEquals(4, AdminUsers.build("admin", "pw", "b", "1", "l", "2", "m", "3").size());
    }

    @Test
    void duplicateOrBlankNamesAreRefused() {
        assertThrows(IllegalStateException.class, () -> AdminUsers.build("admin", "pw", "admin", "1", "l", "", "m", ""));
        assertThrows(IllegalStateException.class, () -> AdminUsers.build("admin", "pw", " ", "1", "l", "", "m", ""));
    }
}
