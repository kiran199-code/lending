package com.bank.lending.lcm.facility;

import com.bank.lending.lcm.common.Audit;
import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.common.Money;
import com.bank.lending.lcm.config.LcmSettings;
import com.bank.lending.lcm.facility.AvailabilityCalculator.Level;
import com.bank.lending.lcm.facility.FacilityDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FacilityService {

    private static final Set<String> TYPES = Set.of("OVERALL", "SUBLIMIT", "PRODUCT");
    private static final Set<String> CHANGES = Set.of("ACTIVATE", "AMEND_LIMIT", "EXTEND_EXPIRY", "SUSPEND", "REINSTATE", "FREEZE", "CLOSE");
    static final int MAX_DEPTH = 4;

    private final FacilityRepository repo;
    private final DrawingPowerProvider drawingPower;
    private final Audit audit;
    private final LcmSettings settings;
    private final Clock clock;

    public FacilityService(FacilityRepository repo, DrawingPowerProvider drawingPower, Audit audit, LcmSettings settings, Clock clock) {
        this.repo = repo;
        this.drawingPower = drawingPower;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- create / read

    @Transactional
    public FacilityView create(CreateFacilityRequest r, String actor) {
        String type = r.facilityType().toUpperCase();
        if (!TYPES.contains(type)) {
            throw LcmException.badRequest("INVALID_FACILITY_TYPE", "facilityType must be one of " + TYPES);
        }
        Facility parent = null;
        if (type.equals("OVERALL")) {
            if (r.parentRef() != null) throw LcmException.badRequest("PARENT_NOT_ALLOWED", "An OVERALL facility has no parent");
            if (r.expiryDate() == null) throw LcmException.badRequest("EXPIRY_REQUIRED", "expiryDate is required for an OVERALL facility");
        } else {
            if (r.parentRef() == null) throw LcmException.badRequest("PARENT_REQUIRED", type + " facility needs a parentRef");
            parent = repo.getByRef(r.parentRef());
            if (parent.status() == FacilityStatus.CLOSED || parent.status() == FacilityStatus.EXPIRED) {
                throw LcmException.unprocessable("PARENT_NOT_OPEN", "Parent " + parent.facilityRef() + " is " + parent.status());
            }
            if (parent.customerId() != r.customerId()) {
                throw LcmException.unprocessable("PARENT_CUSTOMER_MISMATCH", "Parent belongs to a different customer");
            }
            if (!parent.currencyCode().equalsIgnoreCase(r.currencyCode())) {
                throw LcmException.unprocessable("CURRENCY_MISMATCH", "Child currency must equal the parent's " + parent.currencyCode());
            }
            if (repo.chainIds(parent.id()).size() >= MAX_DEPTH) {
                throw LcmException.unprocessable("HIERARCHY_TOO_DEEP", "A facility hierarchy is limited to " + MAX_DEPTH + " levels");
            }
            if (r.sanctionedAmount().compareTo(parent.sanctionedAmount()) > 0) {
                throw LcmException.unprocessable("CHILD_EXCEEDS_PARENT", "Sub-limit " + r.sanctionedAmount() + " exceeds parent limit " + parent.sanctionedAmount());
            }
        }
        LocalDate expiry = r.expiryDate() != null ? r.expiryDate() : parent != null ? parent.expiryDate() : null;
        if (parent != null && parent.expiryDate() != null && expiry != null && expiry.isAfter(parent.expiryDate())) {
            throw LcmException.unprocessable("CHILD_OUTLIVES_PARENT", "Expiry " + expiry + " is after the parent's " + parent.expiryDate());
        }
        if (r.effectiveFrom() != null && expiry != null && expiry.isBefore(r.effectiveFrom())) {
            throw LcmException.badRequest("INVALID_DATES", "expiryDate is before effectiveFrom");
        }
        String ref = r.facilityRef() != null && !r.facilityRef().isBlank() ? r.facilityRef().trim()
                : "FAC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (repo.findByRef(ref).isPresent()) {
            throw LcmException.conflict("FACILITY_EXISTS", "Facility ref already used: " + ref);
        }
        Facility f = new Facility(0, ref, r.customerId(), parent == null ? null : parent.id(), type, r.productCode(),
                r.currencyCode().toUpperCase(), r.branchCode(), r.revolving(), r.dpGoverned(), Money.scale(r.sanctionedAmount()),
                Money.scale(r.maxSingleAmount()), FacilityStatus.DRAFT, r.effectiveFrom(), expiry, r.sanctionRefId(), "MANUAL",
                Money.ZERO, Money.ZERO, Money.ZERO, actor, null, 0);
        repo.insert(f);
        audit.log("FACILITY", ref, "CREATED", actor, Json.obj("sanctioned", f.sanctionedAmount(), "type", type));
        audit.event("FACILITY_CREATED", "FACILITY", ref, Json.obj("facilityRef", ref, "customerId", r.customerId(), "status", "DRAFT"));
        return view(repo.getByRef(ref));
    }

    @Transactional(readOnly = true)
    public FacilityView get(String ref) {
        return view(repo.getByRef(ref));
    }

    @Transactional(readOnly = true)
    public List<FacilityView> search(Long customerId, String status, String parentRef) {
        Long parentId = parentRef == null || parentRef.isBlank() ? null : repo.getByRef(parentRef).id();
        return repo.search(customerId, status, parentId).stream().map(this::view).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TxnView> transactions(String ref, Long beforeId, int limit) {
        Facility f = repo.getByRef(ref);
        return repo.transactions(f.id(), beforeId, Math.max(1, Math.min(limit, 500)));
    }

    // ---------------------------------------------------------------- availability

    /** Chain from the leaf to the root with excess and drawing power resolved. Locks the rows when asked. */
    public List<Level> chainLevels(long leafId, LocalDate asOf, boolean lock) {
        List<Long> ids = repo.chainIds(leafId);
        List<Facility> rows = lock ? repo.lockAll(ids) : ids.stream().sorted().map(repo::getById).collect(Collectors.toList());
        Map<Long, Facility> byId = rows.stream().collect(Collectors.toMap(Facility::id, x -> x));
        List<Level> levels = new ArrayList<>();
        for (Long id : ids) {
            Facility f = byId.get(id);
            BigDecimal excess = repo.activeExcess(id, asOf);
            BigDecimal dp = f.dpGoverned() ? drawingPower.drawingPower(id, asOf) : null;
            levels.add(new Level(f, excess, dp));
        }
        return levels;
    }

    @Transactional(readOnly = true)
    public AvailabilityView availability(String ref, LocalDate asOf) {
        Facility f = repo.getByRef(ref);
        LocalDate date = asOf != null ? asOf : LocalDate.now(clock);
        return availabilityOf(chainLevels(f.id(), date, false));
    }

    /** View for the first (leaf) level of the chain. */
    public AvailabilityView availabilityOf(List<Level> chain) {
        Level leaf = chain.get(0);
        Facility f = leaf.facility();
        BigDecimal avail = AvailabilityCalculator.available(leaf);
        return new AvailabilityView(f.facilityRef(), f.status().name(), f.currencyCode(), f.revolving(), f.dpGoverned(),
                f.sanctionedAmount(), Money.nz(leaf.activeExcess()), leaf.drawingPower(), AvailabilityCalculator.effectiveLimit(leaf),
                f.utilised(), f.drawnTotal(), f.reserved(), AvailabilityCalculator.consumed(f), avail,
                f.status().allowsDrawdown() ? AvailabilityCalculator.effectiveAvailable(chain) : Money.ZERO, avail.signum() < 0);
    }

    @Transactional(readOnly = true)
    public TreeNode tree(String ref, LocalDate asOf) {
        Facility f = repo.getByRef(ref);
        return node(f, asOf != null ? asOf : LocalDate.now(clock), 0);
    }

    private TreeNode node(Facility f, LocalDate asOf, int depth) {
        List<TreeNode> kids = new ArrayList<>();
        if (depth < MAX_DEPTH) {
            for (Facility c : repo.children(f.id())) kids.add(node(c, asOf, depth + 1));
        }
        return new TreeNode(availabilityOf(chainLevels(f.id(), asOf, false)), kids);
    }

    // ---------------------------------------------------------------- maker-checker changes

    @Transactional
    public ChangeView propose(String ref, ProposeChangeRequest r, String maker) {
        String type = r.changeType().toUpperCase();
        if (!CHANGES.contains(type)) throw LcmException.badRequest("INVALID_CHANGE_TYPE", "changeType must be one of " + CHANGES);
        Facility f = repo.getByRef(ref);
        validateChange(f, type, r.newAmount(), r.newExpiry());
        if (repo.pendingChangeExists(f.id(), type)) {
            throw LcmException.conflict("CHANGE_PENDING", "A " + type + " change is already awaiting approval for " + ref);
        }
        String payload = Json.obj("newAmount", r.newAmount() == null ? null : Money.scale(r.newAmount()).toPlainString(),
                "newExpiry", r.newExpiry() == null ? null : r.newExpiry().toString());
        long id = repo.insertChange(f.id(), type, payload, r.reason(), maker);
        audit.log("FACILITY", ref, "CHANGE_PROPOSED", maker, type + " #" + id);
        return repo.findChange(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ChangeView> changes(String status, String facilityRef) {
        return repo.listChanges(status, facilityRef);
    }

    @Transactional
    public ChangeView approve(long changeId, String checker, String note) {
        Facility f = lockedChangeFacility(changeId);
        ChangeView c = repo.findChange(changeId).orElseThrow();
        requirePending(c.status(), "change " + changeId);
        requireDifferentActor(c.maker(), checker);
        Map<String, Object> p = Json.readMap(c.payload());
        BigDecimal newAmount = p.get("newAmount") == null ? null : new BigDecimal(p.get("newAmount").toString());
        LocalDate newExpiry = p.get("newExpiry") == null ? null : LocalDate.parse(p.get("newExpiry").toString());
        validateChange(f, c.changeType(), newAmount, newExpiry);
        apply(f, c.changeType(), newAmount, newExpiry, checker);
        repo.decideChange(changeId, "APPROVED", checker, note);
        audit.log("FACILITY", f.facilityRef(), "CHANGE_APPROVED", checker, c.changeType() + " #" + changeId);
        return repo.findChange(changeId).orElseThrow();
    }

    @Transactional
    public ChangeView reject(long changeId, String checker, String note) {
        Facility f = lockedChangeFacility(changeId);
        ChangeView c = repo.findChange(changeId).orElseThrow();
        requirePending(c.status(), "change " + changeId);
        requireDifferentActor(c.maker(), checker);
        repo.decideChange(changeId, "REJECTED", checker, note);
        audit.log("FACILITY", f.facilityRef(), "CHANGE_REJECTED", checker, c.changeType() + " #" + changeId);
        return repo.findChange(changeId).orElseThrow();
    }

    private Facility lockedChangeFacility(long changeId) {
        long facilityId = repo.lockChange(changeId).orElseThrow(() -> LcmException.notFound("Change", String.valueOf(changeId)));
        return repo.lockAll(List.of(facilityId)).get(0);
    }

    void validateChange(Facility f, String type, BigDecimal newAmount, LocalDate newExpiry) {
        FacilityStatus s = f.status();
        switch (type) {
            case "ACTIVATE" -> {
                if (s != FacilityStatus.DRAFT) throw LcmException.unprocessable("INVALID_STATE", "Only a DRAFT facility can be activated, this one is " + s);
            }
            case "AMEND_LIMIT" -> {
                if (s == FacilityStatus.CLOSED) throw LcmException.unprocessable("INVALID_STATE", "Facility is " + s);
                if (newAmount == null || newAmount.signum() < 0) throw LcmException.badRequest("AMOUNT_REQUIRED", "newAmount (>= 0) is required");
                if (f.parentId() != null) {
                    Facility parent = repo.getById(f.parentId());
                    if (newAmount.compareTo(parent.sanctionedAmount()) > 0) {
                        throw LcmException.unprocessable("CHILD_EXCEEDS_PARENT", "New limit exceeds the parent's " + parent.sanctionedAmount());
                    }
                }
                if (newAmount.compareTo(repo.maxChildSanctioned(f.id())) < 0) {
                    throw LcmException.unprocessable("CHILD_EXCEEDS_PARENT", "New limit is below a sub-limit of this facility");
                }
            }
            case "EXTEND_EXPIRY" -> {
                if (s == FacilityStatus.CLOSED) throw LcmException.unprocessable("INVALID_STATE", "Facility is CLOSED");
                if (newExpiry == null) throw LcmException.badRequest("EXPIRY_REQUIRED", "newExpiry is required");
                if (f.expiryDate() != null && !newExpiry.isAfter(f.expiryDate())) {
                    throw LcmException.unprocessable("NOT_AN_EXTENSION", "newExpiry must be after the current expiry " + f.expiryDate());
                }
                if (f.parentId() != null) {
                    Facility parent = repo.getById(f.parentId());
                    if (parent.expiryDate() != null && newExpiry.isAfter(parent.expiryDate())) {
                        throw LcmException.unprocessable("CHILD_OUTLIVES_PARENT", "newExpiry is after the parent's " + parent.expiryDate());
                    }
                }
            }
            case "SUSPEND" -> {
                if (s != FacilityStatus.ACTIVE) throw LcmException.unprocessable("INVALID_STATE", "Only an ACTIVE facility can be suspended, this one is " + s);
            }
            case "FREEZE" -> {
                if (s != FacilityStatus.ACTIVE && s != FacilityStatus.SUSPENDED) {
                    throw LcmException.unprocessable("INVALID_STATE", "Only an ACTIVE or SUSPENDED facility can be frozen, this one is " + s);
                }
            }
            case "REINSTATE" -> {
                if (s != FacilityStatus.SUSPENDED && s != FacilityStatus.FROZEN) {
                    throw LcmException.unprocessable("INVALID_STATE", "Only a SUSPENDED or FROZEN facility can be reinstated, this one is " + s);
                }
            }
            case "CLOSE" -> {
                if (s == FacilityStatus.CLOSED) throw LcmException.unprocessable("INVALID_STATE", "Facility is already CLOSED");
            }
            default -> throw LcmException.badRequest("INVALID_CHANGE_TYPE", type);
        }
    }

    private void apply(Facility f, String type, BigDecimal newAmount, LocalDate newExpiry, String checker) {
        switch (type) {
            case "ACTIVATE" -> {
                if (f.parentId() != null && repo.getById(f.parentId()).status() != FacilityStatus.ACTIVE) {
                    throw LcmException.unprocessable("PARENT_NOT_ACTIVE", "Parent facility must be ACTIVE first");
                }
                repo.setStatus(f.id(), FacilityStatus.ACTIVE, checker);
                audit.event("FACILITY_STATUS_CHANGED", "FACILITY", f.facilityRef(), Json.obj("facilityRef", f.facilityRef(), "from", f.status().name(), "to", "ACTIVE"));
            }
            case "AMEND_LIMIT" -> {
                repo.setSanctioned(f.id(), Money.scale(newAmount));
                audit.event("FACILITY_LIMIT_CHANGED", "FACILITY", f.facilityRef(),
                        Json.obj("facilityRef", f.facilityRef(), "from", f.sanctionedAmount(), "to", Money.scale(newAmount)));
            }
            case "EXTEND_EXPIRY" -> {
                repo.setExpiry(f.id(), newExpiry);
                if (f.status() == FacilityStatus.EXPIRED && !newExpiry.isBefore(LocalDate.now(clock))) {
                    repo.setStatus(f.id(), FacilityStatus.ACTIVE, null);
                    audit.event("FACILITY_STATUS_CHANGED", "FACILITY", f.facilityRef(), Json.obj("facilityRef", f.facilityRef(), "from", "EXPIRED", "to", "ACTIVE"));
                }
            }
            case "SUSPEND" -> setStatus(f, FacilityStatus.SUSPENDED);
            case "FREEZE" -> setStatus(f, FacilityStatus.FROZEN);
            case "REINSTATE" -> setStatus(f, FacilityStatus.ACTIVE);
            case "CLOSE" -> {
                if (f.utilised().signum() != 0 || f.reserved().signum() != 0) {
                    throw LcmException.unprocessable("FACILITY_IN_USE", "Cannot close: utilised " + f.utilised() + ", reserved " + f.reserved());
                }
                if (repo.openChildren(f.id()) > 0) {
                    throw LcmException.unprocessable("HAS_OPEN_CHILDREN", "Close the sub-limits first");
                }
                setStatus(f, FacilityStatus.CLOSED);
            }
            default -> throw LcmException.badRequest("INVALID_CHANGE_TYPE", type);
        }
    }

    private void setStatus(Facility f, FacilityStatus to) {
        repo.setStatus(f.id(), to, null);
        audit.event("FACILITY_STATUS_CHANGED", "FACILITY", f.facilityRef(), Json.obj("facilityRef", f.facilityRef(), "from", f.status().name(), "to", to.name()));
    }

    // ---------------------------------------------------------------- temporary excess

    @Transactional
    public ExcessView proposeExcess(String ref, ExcessRequest r, String maker) {
        Facility f = repo.getByRef(ref);
        if (f.status() == FacilityStatus.CLOSED || f.status() == FacilityStatus.DRAFT) {
            throw LcmException.unprocessable("INVALID_STATE", "Excess cannot be granted on a " + f.status() + " facility");
        }
        if (r.validTo().isBefore(r.validFrom())) throw LcmException.badRequest("INVALID_DATES", "validTo is before validFrom");
        long id = repo.insertExcess(f.id(), Money.scale(r.amount()), r.validFrom(), r.validTo(), r.reason(), maker);
        audit.log("FACILITY", ref, "EXCESS_PROPOSED", maker, "#" + id + " " + Money.scale(r.amount()));
        return repo.findExcess(id).orElseThrow();
    }

    @Transactional
    public ExcessView approveExcess(long id, String checker) {
        return decideExcess(id, checker, true);
    }

    @Transactional
    public ExcessView rejectExcess(long id, String checker) {
        return decideExcess(id, checker, false);
    }

    private ExcessView decideExcess(long id, String checker, boolean approve) {
        long facilityId = repo.lockExcess(id).orElseThrow(() -> LcmException.notFound("Excess", String.valueOf(id)));
        ExcessView e = repo.findExcess(id).orElseThrow();
        requirePending(e.status(), "excess " + id);
        requireDifferentActor(e.maker(), checker);
        repo.decideExcess(id, approve ? "ACTIVE" : "REJECTED", checker);
        audit.log("FACILITY", e.facilityRef(), approve ? "EXCESS_APPROVED" : "EXCESS_REJECTED", checker, "#" + id);
        if (approve) {
            audit.event("FACILITY_EXCESS_GRANTED", "FACILITY", e.facilityRef(),
                    Json.obj("facilityRef", e.facilityRef(), "amount", e.amount(), "validFrom", e.validFrom().toString(), "validTo", e.validTo().toString()));
        }
        return repo.findExcess(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ExcessView> excess(String ref) {
        repo.getByRef(ref);
        return repo.listExcess(ref);
    }

    @Transactional(readOnly = true)
    public List<ExcessView> excessByStatus(String status) {
        return repo.listExcessByStatus(status);
    }

    // ---------------------------------------------------------------- helpers

    private void requirePending(String status, String what) {
        if (!"PENDING".equals(status)) throw LcmException.conflict("ALREADY_DECIDED", what + " is already " + status);
    }

    private void requireDifferentActor(String maker, String checker) {
        if (settings.makerCheckerEnforce() && maker.equalsIgnoreCase(checker)) {
            throw LcmException.forbidden("MAKER_CHECKER_VIOLATION", "The maker cannot approve or reject their own request");
        }
    }

    FacilityView view(Facility f) {
        String parentRef = f.parentId() == null ? null : repo.getById(f.parentId()).facilityRef();
        return new FacilityView(f.facilityRef(), f.customerId(), parentRef, f.facilityType(), f.productCode(), f.currencyCode(),
                f.branchCode(), f.revolving(), f.dpGoverned(), f.sanctionedAmount(), f.maxSingleAmount(), f.status().name(),
                f.effectiveFrom(), f.expiryDate(), f.sanctionRefId(), f.source(), f.utilised(), f.drawnTotal(), f.reserved(),
                f.createdBy(), f.approvedBy());
    }

    public FacilityView toView(Facility f) {
        return view(f);
    }
}
