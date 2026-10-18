package com.bank.lending.lcm.bridge;

import com.bank.lending.lcm.bridge.BridgeDtos.*;
import com.bank.lending.lcm.collateral.CollateralModels.Collateral;
import com.bank.lending.lcm.collateral.CollateralRepository;
import com.bank.lending.lcm.common.Audit;
import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.common.Money;
import com.bank.lending.lcm.facility.Facility;
import com.bank.lending.lcm.facility.FacilityRepository;
import com.bank.lending.lcm.facility.FacilityStatus;
import com.bank.lending.lcm.utilisation.UtilisationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads existing lending data into LCM through the API: mortgages become collateral, each sanction becomes an
 * implicit ACTIVE facility {@code SANC-<sanctionRefId>}, and loan accounts become opening positions.
 * Every item is idempotent and runs in its own transaction, so a bad row is reported without stopping the batch.
 */
@Service
public class BridgeService {

    public static final String BRIDGE_ACTOR = "bridge";
    public static final String LOAN_ACCOUNT = "LOAN_ACCOUNT";

    private final CollateralRepository collateral;
    private final FacilityRepository facilities;
    private final UtilisationService utilisation;
    private final Audit audit;
    private final TransactionTemplate tx;

    public BridgeService(CollateralRepository collateral, FacilityRepository facilities, UtilisationService utilisation, Audit audit,
                         PlatformTransactionManager txManager) {
        this.collateral = collateral;
        this.facilities = facilities;
        this.utilisation = utilisation;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    public static String facilityRefForSanction(long sanctionRefId) {
        return "SANC-" + sanctionRefId;
    }

    // ---------------------------------------------------------------- mortgages -> collateral

    public BridgeResult importMortgages(MortgageImport req) {
        return run(req.mortgages(), m -> "mortgage " + m.mortgageId(), this::importMortgage);
    }

    private ItemResult importMortgage(MortgageItem m) {
        return tx.execute(status -> {
            String key = "mortgage " + m.mortgageId();
            String type = switch (m.propertyType().toUpperCase()) {
                case "RESIDENTIAL" -> "RESIDENTIAL_PROPERTY";
                case "COMMERCIAL" -> "COMMERCIAL_PROPERTY";
                default -> throw LcmException.badRequest("UNKNOWN_PROPERTY_TYPE", "Unsupported property type " + m.propertyType());
            };
            Collateral c = collateral.findByMortgageId(m.mortgageId()).orElse(null);
            boolean created = false;
            if (c == null) {
                String ref = "MORT-" + m.mortgageId();
                collateral.insert(ref, m.customerId(), type, abbreviate(m.propertyAddress()), (m.currencyCode() == null ? "INR" : m.currencyCode()).toUpperCase(),
                        null, null, Money.ZERO, m.mortgageId(), "BRIDGE", legal(m.legalVerificationStatus()),
                        "REGISTERED".equalsIgnoreCase(m.chargeStatus()), BRIDGE_ACTOR);
                c = collateral.getByRef(ref);
                if ("REGISTERED".equalsIgnoreCase(m.chargeStatus())) {
                    collateral.setCharge(c.id(), m.cersaiRegistrationRef(), null, null);
                }
                audit.log("COLLATERAL", ref, "IMPORTED", BRIDGE_ACTOR, "lending mortgage " + m.mortgageId());
                audit.event("COLLATERAL_CREATED", "COLLATERAL", ref, Json.obj("collateralRef", ref, "customerId", m.customerId(), "type", type, "source", "BRIDGE"));
                created = true;
            }
            int added = addValuation(c, m.valuationDate(), m.valuationAmount(), m.valuerRef());
            if (m.valuationHistory() != null) {
                for (MortgageValuation v : m.valuationHistory()) added += addValuation(c, v.valuationDate(), v.valuationAmount(), v.valuerRef());
            }
            return new ItemResult(key, created ? "CREATED" : (added > 0 ? "VALUATIONS_ADDED" : "SKIPPED"), c.collateralRef(),
                    added + " valuation(s) added");
        });
    }

    private static String abbreviate(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    private int addValuation(Collateral c, LocalDate date, BigDecimal amount, String valuer) {
        if (collateral.bridgeValuationExists(c.id(), date)) return 0;
        collateral.insertValuation(c.id(), date, Money.scale(amount), null, valuer, "BRIDGE", "APPROVED", BRIDGE_ACTOR, BRIDGE_ACTOR);
        return 1;
    }

    private static String legal(String lendingStatus) {
        if (lendingStatus == null) return "PENDING";
        return switch (lendingStatus.toUpperCase()) {
            case "VERIFIED" -> "VERIFIED";
            case "REJECTED" -> "FAILED";
            default -> "PENDING";
        };
    }

    // ---------------------------------------------------------------- sanctions -> facilities

    public BridgeResult importSanctions(SanctionImport req) {
        return run(req.sanctions(), s -> "sanction " + s.sanctionRefId(), this::importSanction);
    }

    private ItemResult importSanction(SanctionItem s) {
        return tx.execute(status -> {
            String key = "sanction " + s.sanctionRefId();
            var existing = facilities.findBySanctionRefId(s.sanctionRefId());
            if (existing.isPresent()) return new ItemResult(key, "SKIPPED", existing.get().facilityRef(), "already imported");
            String ref = facilityRefForSanction(s.sanctionRefId());
            if (facilities.findByRef(ref).isPresent()) {
                throw LcmException.conflict("FACILITY_EXISTS", ref + " exists without a sanction link");
            }
            Facility f = new Facility(0, ref, s.customerId(), null, "OVERALL", s.productCode(), s.currencyCode().toUpperCase(), s.branchCode(),
                    false, false, Money.scale(s.sanctionedAmount()), null, FacilityStatus.ACTIVE, s.effectiveFrom(), s.expiryDate(),
                    s.sanctionRefId(), "BRIDGE", Money.ZERO, Money.ZERO, Money.ZERO, BRIDGE_ACTOR, BRIDGE_ACTOR, 0);
            facilities.insert(f);
            audit.log("FACILITY", ref, "IMPORTED", BRIDGE_ACTOR, "lending sanction " + s.sanctionRefId());
            audit.event("FACILITY_CREATED", "FACILITY", ref, Json.obj("facilityRef", ref, "customerId", s.customerId(), "status", "ACTIVE", "source", "BRIDGE"));
            return new ItemResult(key, "CREATED", ref, null);
        });
    }

    // ---------------------------------------------------------------- loan accounts -> opening positions

    public BridgeResult importPositions(PositionImport req) {
        return run(req.positions(), p -> "account " + p.accountId(), this::importPosition);
    }

    private ItemResult importPosition(PositionItem p) {
        String key = "account " + p.accountId();
        boolean existed = utilisationExists(p.accountId());
        var view = utilisation.openPosition(facilityRefForSanction(p.sanctionRefId()), LOAN_ACCOUNT, String.valueOf(p.accountId()),
                p.drawnAmount(), p.outstandingPrincipal(), BRIDGE_ACTOR);
        return new ItemResult(key, existed ? "SKIPPED" : "CREATED", view.facilityRef(), "outstanding " + view.outstanding());
    }

    private boolean utilisationExists(long accountId) {
        try {
            utilisation.position(LOAN_ACCOUNT, String.valueOf(accountId));
            return true;
        } catch (LcmException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- batch runner

    private <T> BridgeResult run(List<T> items, java.util.function.Function<T, String> keyOf, java.util.function.Function<T, ItemResult> handler) {
        List<ItemResult> results = new ArrayList<>();
        int created = 0, skipped = 0, failed = 0;
        for (T item : items) {
            try {
                ItemResult r = handler.apply(item);
                results.add(r);
                if (r.outcome().equals("SKIPPED")) skipped++; else created++;
            } catch (RuntimeException e) {
                failed++;
                results.add(new ItemResult(keyOf.apply(item), "FAILED", null, e.getMessage()));
            }
        }
        return new BridgeResult(created, skipped, failed, results);
    }
}
