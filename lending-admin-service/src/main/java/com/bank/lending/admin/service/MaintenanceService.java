package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Start from scratch across both services. lending-service clears the loans and products and puts the business
 * date on today's date; lending-lcm-service clears the facilities, utilisation and collateral. The console itself
 * never writes to either database.
 *
 * <p>Order matters: before anything is deleted, LCM is asked whether it allows the clear, so a switched-off or
 * unreachable LCM stops the whole action instead of leaving loans cleared and collateral behind. When LCM is not
 * set up on this console (no LCM_SERVICE_URL / LCM_SERVICE_PASSWORD) only lending-service is cleared and the answer
 * says so.
 */
@Service
public class MaintenanceService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LendingMaintenanceClient lending;
    private final LcmClient lcm;

    public MaintenanceService(LendingMaintenanceClient lending, LcmClient lcm) {
        this.lending = lending;
        this.lcm = lcm;
    }

    /** lending-service's status, plus an {@code lcm} object: configured, reachable, resetEnabled, rowCounts, error. */
    public JsonNode status() {
        ObjectNode out = asObject(lending.status());
        ObjectNode l = out.putObject("lcm");
        l.put("configured", lcm.enabled());
        if (!lcm.enabled()) {
            return out;
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
        return out;
    }

    /** Clears lending-service, then LCM. The result is lending-service's answer with an {@code lcm} object added. */
    public JsonNode clearData(String actor) {
        if (lcm.enabled()) {
            JsonNode st = lcm.get("/maintenance/status", Map.of()); // unreachable or too old: stops here, nothing deleted yet
            if (!st.path("resetEnabled").asBoolean(false)) {
                throw new FeatureDisabledException("Clearing is switched off on lending-lcm-service. Set LCM_RESET_ENABLED=true on it "
                        + "and redeploy; otherwise the loans would be cleared and the facilities and collateral left behind.");
            }
        }
        ObjectNode out = asObject(lending.clearData(actor));
        if (!lcm.enabled()) {
            out.putObject("lcm").put("configured", false);
            out.put("message", out.path("message").asText("") + " Limits and collateral were not cleared because this console is not "
                    + "connected to lending-lcm-service.");
            return out;
        }
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
