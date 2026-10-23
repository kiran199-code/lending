package com.bank.lending.chit.group;

import com.bank.lending.chit.calendar.CycleCalendarGenerator;
import com.bank.lending.chit.calendar.ScheduledCycle;
import com.bank.lending.chit.calendar.WorkingDayCalendar;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Hashing;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.RuleViolation;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.rules.RulesDefaults;
import com.bank.lending.chit.domain.rules.RulesEditPolicy;
import com.bank.lending.chit.domain.rules.RulesValidator;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.EnrolmentRepository;
import com.bank.lending.chit.enrolment.RulesChangePolicy;
import com.bank.lending.chit.group.GroupDtos.CreateGroupRequest;
import com.bank.lending.chit.group.GroupDtos.CycleView;
import com.bank.lending.chit.group.GroupDtos.GroupPage;
import com.bank.lending.chit.group.GroupDtos.GroupView;
import com.bank.lending.chit.group.GroupDtos.PreviewResult;
import com.bank.lending.chit.group.GroupDtos.RulesVersionSummary;
import com.bank.lending.chit.group.GroupDtos.RulesView;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupModels.RulesVersionRow;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Creating a group, changing its rules while that is allowed, and opening enrolment. Every change takes the group's row
 * lock first, so two requests for one group never interleave, and every state-changing call carries an idempotency key.
 */
@Service
public class GroupService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,29}$");

    private final GroupRepository groups;
    private final EnrolmentRepository tickets;
    private final IdempotencyStore idempotency;
    private final Audit audit;

    public GroupService(GroupRepository groups, EnrolmentRepository tickets, IdempotencyStore idempotency, Audit audit) {
        this.groups = groups;
        this.tickets = tickets;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    // ---- create ---------------------------------------------------------------------------------------------------

    @Transactional
    public GroupView create(CreateGroupRequest req, String idemKey, String actor) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        Replay replay = idempotency.begin(idemKey, "GROUP_CREATE", Hashing.sha256(Json.write(req)));
        if (replay != null) {
            return get(requireRef(replay)).asReplay();
        }
        if (req.groupCode() == null || !CODE.matcher(req.groupCode()).matches()) {
            throw ChitException.badRequest("VALIDATION_FAILED",
                    "groupCode must be 3 to 30 characters: capital letters, digits, '-' or '_', starting with a letter or digit");
        }
        text("name", req.name(), 120);
        text("foremanRef", req.foremanRef(), 60);
        ChitRules rules = RulesDefaults.fill(req.rules());
        RulesValidator.validateOrThrow(rules);
        if (groups.exists(req.groupCode())) {
            throw ChitException.conflict("GROUP_CODE_EXISTS", "A group with code " + req.groupCode() + " already exists");
        }
        long id = groups.insert(req.groupCode(), req.name().trim(), req.foremanRef().trim(), rules, actor);
        groups.insertRulesVersion(id, 1, rules, actor);
        audit.log("GROUP", req.groupCode(), "CREATED", actor, "rules version 1");
        audit.event("ChitGroupCreated", "GROUP", req.groupCode(), Json.obj("groupCode", req.groupCode(), "name", req.name().trim(),
                "chitValue", rules.chitValue(), "ticketCount", rules.ticketCount(), "rulesVersion", 1, "actor", actor));
        idempotency.complete(idemKey, req.groupCode());
        return get(req.groupCode());
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public GroupView get(String code) {
        return toView(require(code));
    }

    @Transactional(readOnly = true)
    public GroupPage list(GroupStatus status, int limit, int offset) {
        int size = Math.max(1, Math.min(limit, 200));
        int skip = Math.max(0, offset);
        List<GroupView> items = groups.list(status, size, skip).stream().map(this::toView).toList();
        return new GroupPage(items, groups.count(status), size, skip);
    }

    @Transactional(readOnly = true)
    public RulesView currentRules(String code) {
        GroupRow g = require(code);
        return toRulesView(rulesVersionOf(g, g.currentRulesVersion()));
    }

    @Transactional(readOnly = true)
    public RulesView rulesVersion(String code, int versionNo) {
        GroupRow g = require(code);
        return toRulesView(rulesVersionOf(g, versionNo));
    }

    @Transactional(readOnly = true)
    public List<RulesVersionSummary> rulesVersions(String code) {
        GroupRow g = require(code);
        return groups.rulesVersions(g.id()).stream()
                .map(v -> new RulesVersionSummary(v.versionNo(), v.frozen(), v.frozenAt(), v.createdAt(), v.createdBy())).toList();
    }

    @Transactional(readOnly = true)
    public List<CycleView> cycles(String code) {
        GroupRow g = require(code);
        return groups.cycles(g.id()).stream().map(c -> new CycleView(c.cycleNo(), c.status(), c.dueDate(), c.auctionDate())).toList();
    }

    /** Validation and, when the rules are valid, the cycle dates. Writes nothing. Not transactional: the calendar is a remote call. */
    public PreviewResult preview(ChitRules rules, WorkingDayCalendar calendar) {
        ChitRules effective = RulesDefaults.fill(rules);
        List<RuleViolation> violations = RulesValidator.validate(effective);
        if (!violations.isEmpty()) {
            return new PreviewResult(false, violations, effective, null, null);
        }
        try {
            List<ScheduledCycle> schedule = CycleCalendarGenerator.generate(effective, calendar);
            return new PreviewResult(true, violations, effective, schedule, null);
        } catch (ChitException e) {
            if (e.code().startsWith("CALENDAR_")) {
                return new PreviewResult(true, violations, effective, null, e.code() + ": " + e.getMessage());
            }
            throw e;
        }
    }

    // ---- changes --------------------------------------------------------------------------------------------------

    @Transactional
    public GroupView updateRules(String code, ChitRules newRules, String idemKey, String actor) {
        if (newRules == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body with the rules is required");
        }
        Replay replay = idempotency.begin(idemKey, "RULES_UPDATE", Hashing.sha256(code + "|" + Json.write(newRules)));
        if (replay != null) {
            return get(code).asReplay();
        }
        GroupRow g = lock(code);
        RulesEditPolicy.assertEditable(g.status());
        ChitRules rules = RulesDefaults.fill(newRules);
        RulesValidator.validateOrThrow(rules);
        if (tickets.countLive(g.id()) > 0) {
            List<String> locked = RulesChangePolicy.lockedFieldsChanged(rulesVersionOf(g, g.currentRulesVersion()).rules(), rules);
            if (!locked.isEmpty()) {
                throw ChitException.conflict("RULES_LOCKED_BY_ENROLMENT", "Tickets are already enrolled, so these terms cannot change: "
                        + String.join(", ", locked));
            }
        }
        int versionNo = g.currentRulesVersion() + 1;
        groups.insertRulesVersion(g.id(), versionNo, rules, actor);
        groups.applyRules(g.id(), rules, versionNo, actor);
        audit.log("GROUP", code, "RULES_UPDATED", actor, "rules version " + versionNo);
        audit.event("ChitRulesUpdated", "GROUP", code, Json.obj("groupCode", code, "rulesVersion", versionNo, "actor", actor));
        idempotency.complete(idemKey, code);
        return get(code);
    }

    @Transactional
    public GroupView openEnrolment(String code, String idemKey, String actor) {
        Replay replay = idempotency.begin(idemKey, "ENROLMENT_OPEN", Hashing.sha256(code));
        if (replay != null) {
            return get(code).asReplay();
        }
        GroupRow g = lock(code);
        Lifecycles.GROUP.require(g.status(), GroupStatus.OPEN_FOR_ENROLMENT);
        groups.setStatus(g.id(), GroupStatus.OPEN_FOR_ENROLMENT, actor);
        audit.log("GROUP", code, "ENROLMENT_OPENED", actor, null);
        audit.event("EnrolmentOpened", "GROUP", code, Json.obj("groupCode", code, "seats", g.ticketCount(), "actor", actor));
        idempotency.complete(idemKey, code);
        return get(code);
    }

    // ---- shared helpers (also used by the enrolment and activation services) ---------------------------------------

    /** The group, or NOT_FOUND. No lock. */
    public GroupRow require(String code) {
        return groups.find(code).orElseThrow(() -> ChitException.notFound("Group", code));
    }

    /** The group with its row lock held until the transaction ends. */
    public GroupRow lock(String code) {
        return groups.findForUpdate(code).orElseThrow(() -> ChitException.notFound("Group", code));
    }

    public RulesVersionRow rulesVersionOf(GroupRow g, int versionNo) {
        return groups.rulesVersion(g.id(), versionNo)
                .orElseThrow(() -> ChitException.notFound("Rules version", g.groupCode() + " v" + versionNo));
    }

    public ChitRules rulesOf(GroupRow g) {
        return rulesVersionOf(g, g.currentRulesVersion()).rules();
    }

    public GroupView viewOf(GroupRow g) {
        return toView(g);
    }

    GroupView toView(GroupRow g) {
        int enrolled = tickets.countLive(g.id());
        return new GroupView(g.groupCode(), g.name(), g.foremanRef(), g.status(), g.chitValue(), g.ticketCount(), g.cycleCount(),
                g.subscription(), g.frequency(), g.startDate(), g.currentRulesVersion(), enrolled, Math.max(0, g.ticketCount() - enrolled),
                g.activatedAt(), g.activatedBy(), g.cancelledAt(), g.cancelledBy(), g.cancelReason(), g.version(), g.createdAt(), g.createdBy(), null);
    }

    private static RulesView toRulesView(RulesVersionRow v) {
        return new RulesView(v.versionNo(), v.frozen(), v.createdAt(), v.createdBy(), v.rules());
    }

    private static String requireRef(Replay replay) {
        if (replay.resultRef() == null) {
            throw ChitException.conflict("DATA_CONFLICT", "The first request with this idempotency key has not finished; retry shortly");
        }
        return replay.resultRef();
    }

    static void text(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw ChitException.badRequest("VALIDATION_FAILED", field + " is required");
        }
        if (value.trim().length() > max) {
            throw ChitException.badRequest("VALIDATION_FAILED", field + " must be at most " + max + " characters");
        }
    }
}
