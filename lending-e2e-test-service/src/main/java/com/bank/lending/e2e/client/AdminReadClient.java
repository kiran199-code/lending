package com.bank.lending.e2e.client;

import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import java.time.LocalDate;

/**
 * READ-ONLY verification surface: the admin portal's GET endpoints (which themselves read the lending
 * database through a read-only user). This class has no method that issues anything but GET.
 */
public final class AdminReadClient {

    private final Http http;

    public AdminReadClient(Http http) {
        this.http = http;
    }

    public ApiResult me() {
        return http.get("/api/admin/me");
    }

    /** The business date the portal is using for every "today" (lending-service's, read from the shared database). */
    public ApiResult businessDate() {
        return http.get("/api/admin/business-date");
    }

    public ApiResult account(long accountId) {
        return http.get("/api/admin/accounts/" + accountId);
    }

    public ApiResult schedule(long accountId) {
        return http.get("/api/admin/accounts/" + accountId + "/schedule");
    }

    public ApiResult accruals(long accountId, LocalDate from, LocalDate to) {
        String q = (from == null ? "" : "from=" + from) + (to == null ? "" : (from == null ? "" : "&") + "to=" + to);
        return http.get("/api/admin/accounts/" + accountId + "/accruals" + (q.isEmpty() ? "" : "?" + q));
    }

    public ApiResult payments(long accountId) {
        return http.get("/api/admin/accounts/" + accountId + "/payments");
    }

    public ApiResult ledgerOutbox(int size, int page) {
        return http.get("/api/admin/ledger-outbox?size=" + size + "&page=" + page);
    }

    public Http http() {
        return http;
    }

    public ApiResult accountsByCustomer(long customerId) {
        return http.get("/api/admin/accounts?customerId=" + customerId + "&size=50");
    }
}
