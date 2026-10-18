package com.bank.lending.lcm.collateral;

import com.bank.lending.lcm.collateral.CollateralDtos.*;
import com.bank.lending.lcm.collateral.CollateralModels.*;
import com.bank.lending.lcm.collateral.ValuationCalculator.Contribution;
import com.bank.lending.lcm.common.Audit;
import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.common.Money;
import com.bank.lending.lcm.config.LcmSettings;
import com.bank.lending.lcm.facility.DrawingPowerProvider;
import com.bank.lending.lcm.facility.Facility;
import com.bank.lending.lcm.facility.FacilityRepository;
import com.bank.lending.lcm.facility.FacilityStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CollateralService implements DrawingPowerProvider {

    private static final Set<String> LEGAL = Set.of("PENDING", "VERIFIED", "FAILED");

    private final CollateralRepository repo;
    private final FacilityRepository facilities;
    private final Audit audit;
    private final LcmSettings settings;
    private final Clock clock;

    public CollateralService(CollateralRepository repo, FacilityRepository facilities, Audit audit, LcmSettings settings, Clock clock) {
        this.repo = repo;
        this.facilities = facilities;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- types

    @Transactional(readOnly = true)
    public List<TypeConfigView> types() {
        return repo.types().stream().map(t -> new TypeConfigView(t.typeCode(), t.description(), t.defaultHaircutPct(), t.maxLtvPct(),
                t.validityDays(), t.requiresCharge(), t.requiresInsurance())).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- register

    @Transactional
    public CollateralView create(CreateCollateralRequest r, String actor) {
        TypeConfig type = repo.type(r.collateralType().toUpperCase())
                .filter(TypeConfig::active)
                .orElseThrow(() -> LcmException.badRequest("UNKNOWN_COLLATERAL_TYPE", "Unknown or inactive collateral type " + r.collateralType()));
        String ref = r.collateralRef() != null && !r.collateralRef().isBlank() ? r.collateralRef().trim()
                : "COL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (repo.findByRef(ref).isPresent()) throw LcmException.conflict("COLLATERAL_EXISTS", "Collateral ref already used: " + ref);
        repo.insert(ref, r.customerId(), type.typeCode(), r.description(), r.currencyCode().toUpperCase(), r.haircutPct(), r.maxLtvPct(),
                Money.scale(Money.nz(r.priorChargeAmount())), null, "MANUAL", "PENDING", false, actor);
        audit.log("COLLATERAL", ref, "CREATED", actor, type.typeCode());
        audit.event("COLLATERAL_CREATED", "COLLATERAL", ref, Json.obj("collateralRef", ref, "customerId", r.customerId(), "type", type.typeCode()));
        return view(repo.getByRef(ref));
    }

    @Transactional(readOnly = true)
    public CollateralView get(String ref) {
        return view(repo.getByRef(ref));
    }

    @Transactional(readOnly = true)
    public List<CollateralView> search(Long customerId, String status) {
        return repo.search(customerId, status).stream().map(this::view).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- valuations (maker-checker)

    @Transactional
    public ValuationView proposeValuation(String ref, ValuationRequest r, String maker) {
        Collateral c = repo.lockByRef(ref);
        if (c.status().equals("RELEASED")) throw LcmException.unprocessable("INVALID_STATE", "Collateral is RELEASED");
        if (r.valuationDate().isAfter(LocalDate.now(clock))) throw LcmException.badRequest("INVALID_DATE", "valuationDate cannot be in the future");
        if (r.forcedSaleValue() != null && r.forcedSaleValue().compareTo(r.marketValue()) > 0) {
            throw LcmException.badRequest("INVALID_VALUE", "forcedSaleValue cannot exceed marketValue");
        }
        long id = repo.insertValuation(c.id(), r.valuationDate(), Money.scale(r.marketValue()), Money.scale(r.forcedSaleValue()),
                r.valuerRef(), "MANUAL", "PENDING", maker, null);
        audit.log("COLLATERAL", ref, "VALUATION_PROPOSED", maker, "#" + id + " " + Money.scale(r.marketValue()));
        return repo.valuationView(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ValuationView> valuations(String ref) {
        repo.getByRef(ref);
        return repo.valuations(ref);
    }

    @Transactional(readOnly = true)
    public List<ValuationView> valuationsByStatus(String status) {
        return repo.valuationsByStatus(status);
    }

    @Transactional
    public ValuationView approveValuation(long id, String checker) {
        return decideValuation(id, checker, true);
    }

    @Transactional
    public ValuationView rejectValuation(long id, String checker) {
        return decideValuation(id, checker, false);
    }

    private ValuationView decideValuation(long id, String checker, boolean approve) {
        Valuation v = repo.lockValuation(id).orElseThrow(() -> LcmException.notFound("Valuation", String.valueOf(id)));
        if (!v.status().equals("PENDING")) throw LcmException.conflict("ALREADY_DECIDED", "Valuation " + id + " is already " + v.status());
        if (settings.makerCheckerEnforce() && v.maker().equalsIgnoreCase(checker)) {
            throw LcmException.forbidden("MAKER_CHECKER_VIOLATION", "The maker cannot approve or reject their own valuation");
        }
        Collateral c = repo.lockByRef(repo.getById(v.collateralId()).collateralRef());
        repo.decideValuation(id, approve ? "APPROVED" : "REJECTED", checker);
        audit.log("COLLATERAL", c.collateralRef(), approve ? "VALUATION_APPROVED" : "VALUATION_REJECTED", checker, "#" + id);
        if (approve) {
            audit.event("COLLATERAL_VALUED", "COLLATERAL", c.collateralRef(), Json.obj("collateralRef", c.collateralRef(),
                    "valuationDate", v.valuationDate().toString(), "baseValue", v.baseValue()));
            announceDrawingPower(c);
        }
        return repo.valuationView(id).orElseThrow();
    }

    // ---------------------------------------------------------------- legal, charge, insurance, lifecycle

    @Transactional
    public CollateralView setLegalStatus(String ref, String status, String actor) {
        String s = status.toUpperCase();
        if (!LEGAL.contains(s)) throw LcmException.badRequest("INVALID_LEGAL_STATUS", "status must be one of " + LEGAL);
        Collateral c = repo.lockByRef(ref);
        repo.setLegal(c.id(), s);
        if (s.equals("FAILED") && c.status().equals("ACTIVE")) {
            repo.setStatus(c.id(), "SUSPENDED");
            audit.event("COLLATERAL_SUSPENDED", "COLLATERAL", ref, Json.obj("collateralRef", ref, "reason", "LEGAL_FAILED"));
            announceDrawingPower(c);
        }
        audit.log("COLLATERAL", ref, "LEGAL_" + s, actor, null);
        return view(repo.getByRef(ref));
    }

    @Transactional
    public CollateralView registerCharge(String ref, ChargeRequest r, String actor) {
        Collateral c = repo.lockByRef(ref);
        repo.setCharge(c.id(), r.chargeRef(), r.registeredOn() != null ? r.registeredOn() : LocalDate.now(clock),
                r.priorChargeAmount() == null ? null : Money.scale(r.priorChargeAmount()));
        audit.log("COLLATERAL", ref, "CHARGE_REGISTERED", actor, r.chargeRef());
        if (c.status().equals("ACTIVE")) announceDrawingPower(c);
        return view(repo.getByRef(ref));
    }

    @Transactional
    public CollateralView setInsurance(String ref, InsuranceRequest r, String actor) {
        Collateral c = repo.lockByRef(ref);
        repo.setInsurance(c.id(), r.expiry());
        audit.log("COLLATERAL", ref, "INSURANCE_UPDATED", actor, r.expiry().toString());
        if (c.status().equals("ACTIVE")) announceDrawingPower(c);
        return view(repo.getByRef(ref));
    }

    @Transactional
    public CollateralView activate(String ref, String actor) {
        Collateral c = repo.lockByRef(ref);
        if (c.status().equals("ACTIVE")) return view(c);
        if (c.status().equals("RELEASED")) throw LcmException.unprocessable("INVALID_STATE", "Collateral is RELEASED");
        List<String> unmet = unmet(c);
        if (!unmet.isEmpty()) {
            throw LcmException.unprocessable("ACTIVATION_REQUIREMENTS_NOT_MET", "Cannot activate " + ref + ": " + String.join(", ", unmet));
        }
        repo.setStatus(c.id(), "ACTIVE");
        audit.log("COLLATERAL", ref, "ACTIVATED", actor, null);
        audit.event("COLLATERAL_ACTIVATED", "COLLATERAL", ref, Json.obj("collateralRef", ref));
        announceDrawingPower(c);
        return view(repo.getByRef(ref));
    }

    @Transactional
    public CollateralView release(String ref, String actor) {
        Collateral c = repo.lockByRef(ref);
        if (c.status().equals("RELEASED")) return view(c);
        if (!repo.activeFacilityIdsFor(c.id()).isEmpty()) {
            throw LcmException.unprocessable("HAS_ACTIVE_ALLOCATIONS", "Release the allocations to facilities first");
        }
        repo.setStatus(c.id(), "RELEASED");
        audit.log("COLLATERAL", ref, "RELEASED", actor, null);
        audit.event("COLLATERAL_RELEASED", "COLLATERAL", ref, Json.obj("collateralRef", ref));
        return view(repo.getByRef(ref));
    }

    // ---------------------------------------------------------------- allocations

    @Transactional
    public AllocationView allocate(String ref, AllocationRequest r, String actor) {
        Collateral c = repo.lockByRef(ref);
        if (!c.status().equals("ACTIVE")) throw LcmException.unprocessable("COLLATERAL_NOT_ACTIVE", "Collateral " + ref + " is " + c.status());
        Facility f = facilities.getByRef(r.facilityRef());
        if (f.status() == FacilityStatus.CLOSED) throw LcmException.unprocessable("INVALID_STATE", "Facility " + f.facilityRef() + " is CLOSED");
        if (!f.currencyCode().equalsIgnoreCase(c.currencyCode())) {
            throw LcmException.unprocessable("CURRENCY_MISMATCH", "Collateral is in " + c.currencyCode() + ", facility in " + f.currencyCode());
        }
        if (repo.activeAllocationExists(c.id(), f.id())) {
            throw LcmException.conflict("ALLOCATION_EXISTS", "Collateral is already allocated to " + f.facilityRef());
        }
        BigDecimal pct = r.allocationPct().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = repo.activeAllocationPct(c.id()).add(pct);
        if (total.compareTo(Money.HUNDRED) > 0) {
            throw LcmException.unprocessable("OVER_ALLOCATED", "Total allocation would be " + total + "% of the collateral");
        }
        long id = repo.insertAllocation(c.id(), f.id(), pct, actor);
        audit.log("COLLATERAL", ref, "ALLOCATED", actor, f.facilityRef() + " " + pct + "%");
        audit.event("ALLOCATION_CREATED", "COLLATERAL", ref, Json.obj("collateralRef", ref, "facilityRef", f.facilityRef(), "pct", pct));
        announceFacilityDrawingPower(f);
        return repo.allocationView(id).orElseThrow();
    }

    @Transactional
    public AllocationView releaseAllocation(long id, String actor) {
        Allocation a = repo.lockAllocation(id).orElseThrow(() -> LcmException.notFound("Allocation", String.valueOf(id)));
        if (a.status().equals("RELEASED")) return repo.allocationView(id).orElseThrow();
        repo.releaseAllocation(id);
        Collateral c = repo.getById(a.collateralId());
        Facility f = facilities.getById(a.facilityId());
        audit.log("COLLATERAL", c.collateralRef(), "ALLOCATION_RELEASED", actor, f.facilityRef());
        audit.event("ALLOCATION_RELEASED", "COLLATERAL", c.collateralRef(), Json.obj("collateralRef", c.collateralRef(), "facilityRef", f.facilityRef()));
        announceFacilityDrawingPower(f);
        return repo.allocationView(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<AllocationView> allocations(String ref) {
        repo.getByRef(ref);
        return repo.allocationViews(ref);
    }

    // ---------------------------------------------------------------- drawing power

    @Override
    @Transactional(readOnly = true)
    public BigDecimal drawingPower(long facilityId, LocalDate asOf) {
        return contributions(facilityId, asOf).stream().map(Contribution::contribution).reduce(Money.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public DrawingPowerView drawingPowerView(String facilityRef, LocalDate asOf) {
        Facility f = facilities.getByRef(facilityRef);
        LocalDate date = asOf != null ? asOf : LocalDate.now(clock);
        List<Contribution> list = contributions(f.id(), date);
        BigDecimal total = list.stream().map(Contribution::contribution).reduce(Money.ZERO, BigDecimal::add);
        return new DrawingPowerView(f.facilityRef(), date, f.dpGoverned(), total, list);
    }

    private List<Contribution> contributions(long facilityId, LocalDate asOf) {
        return repo.drawingPowerInputs(facilityId).stream().map(in -> ValuationCalculator.contribution(in, asOf)).collect(Collectors.toList());
    }

    private void announceDrawingPower(Collateral c) {
        for (Long fid : repo.activeFacilityIdsFor(c.id())) announceFacilityDrawingPower(facilities.getById(fid));
    }

    private void announceFacilityDrawingPower(Facility f) {
        if (!f.dpGoverned()) return;
        audit.event("DRAWING_POWER_CHANGED", "FACILITY", f.facilityRef(), Json.obj("facilityRef", f.facilityRef(),
                "drawingPower", drawingPower(f.id(), LocalDate.now(clock))));
    }

    /**
     * Whether the loans drawn on a facility are backed by collateral: ACTIVE collateral of the facility's customer with an ACTIVE
     * allocation to the facility or to a parent facility. {@code coveredValue} is the lendable value times each allocation share;
     * it is information, the yes/no answer is {@code covered}.
     */
    @Transactional(readOnly = true)
    public CoverView cover(String facilityRef) {
        Facility f = facilities.getByRef(facilityRef);
        List<CoverRow> rows = repo.coverRows(facilities.chainIds(f.id()), f.customerId());
        BigDecimal total = BigDecimal.ZERO;
        java.util.ArrayList<CoverItem> items = new java.util.ArrayList<>();
        for (CoverRow r : rows) {
            CollateralView v = view(repo.getByRef(r.collateralRef()));
            BigDecimal covered = v.lendableValue() == null ? BigDecimal.ZERO
                    : Money.scale(v.lendableValue().multiply(r.allocationPct()).divide(Money.HUNDRED, 2, java.math.RoundingMode.HALF_UP));
            total = total.add(covered);
            items.add(new CoverItem(r.collateralRef(), v.collateralType(), r.facilityRef(), r.allocationPct(), v.lendableValue(), covered));
        }
        return new CoverView(f.facilityRef(), f.customerId(), !items.isEmpty(), Money.scale(total), items);
    }

    // ---------------------------------------------------------------- view helpers

    List<String> unmet(Collateral c) {
        TypeConfig t = repo.type(c.collateralType()).orElseThrow();
        var latest = repo.latestApproved(c.id());
        return ActivationRules.unmet(c.legalStatus(), latest.isPresent(), latest.map(Valuation::valuationDate).orElse(null),
                t.validityDays(), t.requiresCharge(), c.chargeRegistered(), t.requiresInsurance(), c.insuranceExpiry(), LocalDate.now(clock));
    }

    CollateralView view(Collateral c) {
        TypeConfig t = repo.type(c.collateralType()).orElseThrow();
        BigDecimal haircut = c.haircutOverride() != null ? c.haircutOverride() : t.defaultHaircutPct();
        BigDecimal ltv = c.ltvOverride() != null ? c.ltvOverride() : t.maxLtvPct();
        var latest = repo.latestApproved(c.id());
        ValuationView lv = latest.flatMap(v -> repo.valuationView(v.id())).orElse(null);
        BigDecimal eligible = null;
        BigDecimal lendable = null;
        if (latest.isPresent()) {
            eligible = ValuationCalculator.eligibleValue(latest.get().baseValue(), haircut, c.priorChargeAmount());
            lendable = ValuationCalculator.lendableValue(eligible, ltv);
        }
        List<String> unmet = c.status().equals("DRAFT") ? unmet(c) : List.of();
        return new CollateralView(c.collateralRef(), c.customerId(), c.collateralType(), c.description(), c.currencyCode(), c.status(),
                c.legalStatus(), c.chargeRegistered(), c.chargeRef(), c.chargeRegisteredOn(), c.priorChargeAmount(), c.insuranceExpiry(),
                haircut, ltv, c.lendingMortgageId(), c.source(), lv, eligible, lendable, unmet);
    }

    public CollateralView toView(Collateral c) {
        return view(c);
    }
}
