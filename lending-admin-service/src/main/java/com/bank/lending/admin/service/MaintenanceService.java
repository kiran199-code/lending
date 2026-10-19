package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Start from scratch across three services. lending-service clears the loans and products and puts the business
 * date on today's date; lending-lcm-service clears the facilities, utilisation and collateral; lending-batch-service
 * empties the job execution history, so a job is not refused for a business date it already ran for before the clear.
 * The console itself never writes to any database.
 *
 * <p>Order matters: before anything is deleted, LCM and the batch service are asked whether they allow the clear (and the
 * batch service whether it is busy), so a switched-off, busy or unreachable one stops the whole action instead of leaving
 * loans cleared and the rest behind. When LCM or the batch service is not set up on this console (no LCM_SERVICE_URL /
 * LCM_SERVICE_PASSWORD, or no BATCH_SERVICE_URL / BATCH_ADMIN_PASSWORD) that part is skipped and the answer says so.
 */
@Service
public class MaintenanceService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LendingMaintenanceClient lending;
    private final LcmClient lcm;
    private final BatchConfigClient batch;

    public MaintenanceService(LendingMaintenanceClient lending, LcmClient lcm, BatchConfigClient batch) {
        this.lending = lending;
        this.lcm = lcm;
        this.batch = batch;
    }

    /**
     * lending-service's status, plus an {@code lcm} object (configured, reachable, resetEnabled, rowCounts, error) and a
     * {@code batch} object (the same, plus busy and busyReason).
     */
    public JsonNode status() {
        ObjectNode out = asObject(lending.status());
        lcmStatus(out.putObject("lcm"));
        batchStatus(out.putObject("batch"));
        return out;
    }

    private void lcmStatus(ObjectNode l) {
        l.put("configured", lcm.enabled());
        if (!lcm.enabled()) {
            return;
        }
        try {
            JsonNode st = lcm.get("/maintenance/status", Map.of());
            l.put("reachable", true);
            l.put("resetEnabled", st.path("resetEnabled").asBoolean(false));
            l.set("rowCounts", st.path("rowCounts").isMissingNode() ? JSON.createObjectNode() : st.get("rowCounts"));
        } catch (RuntimeException e) {
            // The page must still load when LCM is asleep or an older version without the maintenance endpoints.
            l.put("reachable", false);
            l.put("resetEnabled", false);
            l.set("rowCounts", JSON.createObjectNode());
            l.put("error", e.getMessage());
        }
    }

    private void batchStatus(ObjectNode b) {
        b.put("configured", batch.enabled());
        if (!batch.enabled()) {
            return;
        }
        try {
            JsonNode st = batch.maintenanceStatus();
            b.put("reachable", true);
            b.put("resetEnabled", st.path("resetEnabled").asBoolean(false));
            b.put("busy", st.path("busy").asBoolean(false));
            if (st.hasNonNull("busyReason")) {
                b.put("busyReason", st.get("busyReason").asText());
            }
            b.set("rowCounts", st.path("rowCounts").isMissingNode() ? JSON.createObjectNode() : st.get("rowCounts"));
        } catch (RuntimeException e) {
            // Same as LCM: the page must still load when the batch service is asleep or an older version without the endpoints.
            b.put("reachable", false);
            b.put("resetEnabled", false);
            b.put("busy", false);
            b.set("rowCounts", JSON.createObjectNode());
            b.put("error", e.getMessage());
        }
    }

    /**
     * Clears lending-service, then LCM, then the batch history. The result is lending-service's answer with an {@code lcm}
     * and a {@code batch} object added.
     */
    public JsonNode clearData(String actor) {
        if (lcm.enabled()) {
            JsonNode st = lcm.get("/maintenance/status", Map.of()); // unreachable or too old: stops here, nothing deleted yet
            if (!st.path("resetEnabled").asBoolean(false)) {
                throw new FeatureDisabledException("Clearing is switched off on lending-lcm-service. Set LCM_RESET_ENABLED=true on it "
                        + "and redeploy; otherwise the loans would be cleared and the facilities and collateral left behind.");
            }
        }
        if (batch.enabled()) {
            JsonNode st = batch.maintenanceStatus(); // unreachable or too old: stops here, nothing deleted yet
            if (!st.path("resetEnabled").asBoolean(false)) {
                throw new FeatureDisabledException("Clearing is switched off on lending-batch-service. Set BATCH_RESET_ENABLED=true on it "
                        + "and redeploy; otherwise the loans would be cleared and the old job history left behind, and end of day would refuse "
                        + "dates that already ran.");
            }
            if (st.path("busy").asBoolean(false)) {
                throw new UpstreamException(409, st.path("busyReason").asText("The batch service is busy") + ". Wait for it to finish, then clear again. Nothing was deleted.");
            }
        }
        ObjectNode out = asObject(lending.clearData(actor));
        if (lcm.enabled()) {
            try {
                JsonNode r = lcm.post("/maintenance/clear-data", Map.of(), Map.of("confirm", "RESET"), actor);
                ObjectNode l = asObject(r);
                l.put("configured", true);
                out.set("lcm", l);
                out.put("message", out.path("message").asText("") + " " + l.path("message").asText(""));
            } catch (UpstreamException e) {
                throw new UpstreamException(e.getStatus(), "The loans and products were cleared and the business date was reset, but clearing "
                        + "limits and collateral failed: " + e.getMessage() + " Press Clear all data again to retry (it clears the loans a second time, which is harmless).");
            }
        } else {
            out.putObject("lcm").put("configured", false);
            out.put("message", out.path("message").asText("") + " Limits and collateral were not cleared because this console is not "
                    + "connected to lending-lcm-service.");
        }
        if (batch.enabled()) {
            try {
                ObjectNode b = asObject(batch.maintenanceClear(actor));
                b.put("configured", true);
                out.set("batch", b);
                out.put("message", out.path("message").asText("") + " " + b.path("message").asText(""));
            } catch (UpstreamException e) {
                throw new UpstreamException(e.getStatus(), "The loans, products and limits were cleared and the business date was reset, but clearing "
                        + "the batch job history failed: " + e.getMessage() + " Press Clear all data again to retry (it clears the rest a second time, which is harmless).");
            }
        } else {
            out.putObject("batch").put("configured", false);
            out.put("message", out.path("message").asText("") + " The batch job history was not cleared because this console is not "
                    + "connected to lending-batch-service.");
        }
        return out;
    }

    private static ObjectNode asObject(JsonNode n) {
        if (n instanceof ObjectNode o) {
            return o;
        }
        ObjectNode wrap = JSON.createObjectNode();
        if (n != null && !n.isNull()) {
            wrap.set("result", n);
        }
        return wrap;
    }
}
