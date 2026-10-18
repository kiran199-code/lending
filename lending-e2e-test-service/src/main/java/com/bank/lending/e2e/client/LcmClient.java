package com.bank.lending.e2e.client;

import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Writes to the Limits and Collateral Management service go ONLY through its public REST API, as the named actor
 * (X-Actor decides maker versus checker; the same person can never approve what they proposed).
 */
public final class LcmClient {

    private static final String BASE = "/api/v1/lcm";

    private final Http http;

    public LcmClient(Http http) {
        this.http = http;
    }

    public Http http() {
        return http;
    }

    private static Map<String, String> as(String actor) {
        return Map.of("X-Actor", actor);
    }

    // ---- collateral ----

    public ApiResult collateralTypes() {
        return http.get(BASE + "/collateral-types");
    }

    public ApiResult createCollateral(ObjectNode body, String actor) {
        return http.post(BASE + "/collateral", body, as(actor));
    }

    public ApiResult collateral(String ref) {
        return http.get(BASE + "/collateral/" + ref);
    }

    public ApiResult setLegal(String ref, String status, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("status", status);
        return http.post(BASE + "/collateral/" + ref + "/legal-verification", b, as(actor));
    }

    public ApiResult proposeValuation(String ref, LocalDate date, BigDecimal marketValue, String valuer, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("valuationDate", date.toString());
        b.put("marketValue", marketValue);
        b.put("valuerRef", valuer);
        return http.post(BASE + "/collateral/" + ref + "/valuations", b, as(actor));
    }

    public ApiResult approveValuation(long id, String actor) {
        return http.post(BASE + "/valuations/" + id + "/approve", null, as(actor));
    }

    public ApiResult registerCharge(String ref, String chargeRef, LocalDate on, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("chargeRef", chargeRef);
        b.put("registeredOn", on.toString());
        return http.post(BASE + "/collateral/" + ref + "/charge", b, as(actor));
    }

    public ApiResult setInsurance(String ref, LocalDate expiry, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("expiry", expiry.toString());
        return http.post(BASE + "/collateral/" + ref + "/insurance", b, as(actor));
    }

    public ApiResult activateCollateral(String ref, String actor) {
        return http.post(BASE + "/collateral/" + ref + "/activate", null, as(actor));
    }

    public ApiResult releaseCollateral(String ref, String actor) {
        return http.post(BASE + "/collateral/" + ref + "/release", null, as(actor));
    }

    public ApiResult allocate(String ref, String facilityRef, BigDecimal pct, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("facilityRef", facilityRef);
        b.put("allocationPct", pct);
        return http.post(BASE + "/collateral/" + ref + "/allocations", b, as(actor));
    }

    public ApiResult allocations(String ref) {
        return http.get(BASE + "/collateral/" + ref + "/allocations");
    }

    public ApiResult releaseAllocation(long id, String actor) {
        return http.post(BASE + "/allocations/" + id + "/release", null, as(actor));
    }

    // ---- facilities ----

    public ApiResult createFacility(ObjectNode body, String actor) {
        return http.post(BASE + "/facilities", body, as(actor));
    }

    public ApiResult proposeChange(String facilityRef, String changeType, String reason, String actor) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("changeType", changeType);
        b.put("reason", reason);
        return http.post(BASE + "/facilities/" + facilityRef + "/changes", b, as(actor));
    }

    public ApiResult approveChange(long id, String actor) {
        return http.post(BASE + "/facilities/changes/" + id + "/approve", null, as(actor));
    }

    public ApiResult facility(String ref) {
        return http.get(BASE + "/facilities/" + ref);
    }

    public ApiResult availability(String ref) {
        return http.get(BASE + "/facilities/" + ref + "/availability");
    }

    public ApiResult collateralCover(String ref) {
        return http.get(BASE + "/facilities/" + ref + "/collateral-cover");
    }
}
