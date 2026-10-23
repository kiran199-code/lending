package com.bank.lending.chit.group;

import com.bank.lending.chit.common.Actor;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.enrolment.ActivationService;
import com.bank.lending.chit.enrolment.EnrolmentDtos.ActivationResult;
import com.bank.lending.chit.enrolment.EnrolmentDtos.CancelRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.CancelResult;
import com.bank.lending.chit.enrolment.EnrolmentDtos.QuoteView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.Readiness;
import com.bank.lending.chit.enrolment.EnrolmentService;
import com.bank.lending.chit.group.GroupDtos.CreateGroupRequest;
import com.bank.lending.chit.group.GroupDtos.CycleView;
import com.bank.lending.chit.group.GroupDtos.GroupPage;
import com.bank.lending.chit.group.GroupDtos.GroupView;
import com.bank.lending.chit.group.GroupDtos.PreviewRequest;
import com.bank.lending.chit.group.GroupDtos.PreviewResult;
import com.bank.lending.chit.group.GroupDtos.RulesVersionSummary;
import com.bank.lending.chit.group.GroupDtos.RulesView;
import com.bank.lending.chit.platform.LendingPlatformClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Group set-up: create, rules, open enrolment, activate, cancel. The business date and the working-day calendar are
 * read from lending-service here, before any database transaction starts; if lending-service cannot be reached the call
 * answers 503 BUSINESS_DATE_UNAVAILABLE and nothing is changed.
 */
@RestController
@RequestMapping("/api/v1/chit/groups")
@Tag(name = "Groups", description = "Create, configure, activate and cancel chit groups")
public class GroupController {

    private final GroupService groups;
    private final EnrolmentService enrolment;
    private final ActivationService activation;
    private final LendingPlatformClient platform;

    public GroupController(GroupService groups, EnrolmentService enrolment, ActivationService activation, LendingPlatformClient platform) {
        this.groups = groups;
        this.enrolment = enrolment;
        this.activation = activation;
        this.platform = platform;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a group in DRAFT with validated rules (chit.group.write)",
            description = "Needs Idempotency-Key and X-Actor. Optional rules are filled with defaults; the rules must reconcile "
                    + "or the call answers 422 CONFIG_INVALID listing every violation.")
    public GroupView create(@RequestBody CreateGroupRequest request,
                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return groups.create(request, idempotencyKey, Actor.required(actor));
    }

    @GetMapping
    @Operation(summary = "Groups, newest first; filter by status (chit.group.read)")
    public GroupPage list(@RequestParam(value = "status", required = false) String status,
                          @RequestParam(value = "limit", defaultValue = "50") int limit,
                          @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return groups.list(parseStatus(status), limit, offset);
    }

    @PostMapping("/preview")
    @Operation(summary = "Validate rules and show the cycle dates without saving anything (chit.group.read)",
            description = "Reads the working-day calendar from lending-service.")
    public PreviewResult preview(@RequestBody PreviewRequest request) {
        return groups.preview(request == null ? null : request.rules(), platform.calendar());
    }

    @GetMapping("/{code}")
    @Operation(summary = "One group (chit.group.read)")
    public GroupView get(@PathVariable String code) {
        return groups.get(code);
    }

    @GetMapping("/{code}/rules")
    @Operation(summary = "The group's current rules version (chit.group.read)")
    public RulesView rules(@PathVariable String code) {
        return groups.currentRules(code);
    }

    @GetMapping("/{code}/rules/versions")
    @Operation(summary = "All rules versions of the group, oldest first (chit.group.read)")
    public List<RulesVersionSummary> rulesVersions(@PathVariable String code) {
        return groups.rulesVersions(code);
    }

    @GetMapping("/{code}/rules/versions/{versionNo}")
    @Operation(summary = "One rules version (chit.group.read)")
    public RulesView rulesVersion(@PathVariable String code, @PathVariable int versionNo) {
        return groups.rulesVersion(code, versionNo);
    }

    @PutMapping("/{code}/rules")
    @Operation(summary = "Replace the rules while the group is DRAFT or OPEN_FOR_ENROLMENT; creates a new version (chit.group.write)",
            description = "Once tickets are enrolled the money terms (value, tickets, subscription, frequency, fees, security) are locked. "
                    + "Needs Idempotency-Key and X-Actor.")
    public GroupView updateRules(@PathVariable String code, @RequestBody ChitRules rules,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        return groups.updateRules(code, rules, idempotencyKey, Actor.required(actor));
    }

    @PostMapping("/{code}/open-enrolment")
    @Operation(summary = "DRAFT to OPEN_FOR_ENROLMENT (chit.group.write)", description = "Needs Idempotency-Key and X-Actor.")
    public GroupView openEnrolment(@PathVariable String code,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return groups.openEnrolment(code, idempotencyKey, Actor.required(actor));
    }

    @GetMapping("/{code}/enrolment-quote")
    @Operation(summary = "What each ticket must pay at enrolment: fees and security (chit.group.read)")
    public QuoteView quote(@PathVariable String code) {
        return enrolment.quote(code);
    }

    @GetMapping("/{code}/cycles")
    @Operation(summary = "The cycle calendar (empty until the group is activated) (chit.group.read)")
    public List<CycleView> cycles(@PathVariable String code) {
        return groups.cycles(code);
    }

    @GetMapping("/{code}/activation-check")
    @Operation(summary = "Whether the group can be activated now, with every blocker listed (chit.group.read)",
            description = "Reads the business date and working-day calendar from lending-service.")
    public Readiness activationCheck(@PathVariable String code) {
        return activation.readiness(code, platform.today(), platform.calendar());
    }

    @PostMapping("/{code}/activate")
    @Operation(summary = "Freeze the rules, create the cycle calendar and ledger accounts, and start the group (chit.group.activate)",
            description = "Needs Idempotency-Key and X-Actor. Refused 409 ACTIVATION_BLOCKED with every reason if the group is not "
                    + "FULL, tickets or security are missing, or the first instalment date is before the business date.")
    public ActivationResult activate(@PathVariable String code,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestHeader(value = "X-Actor", required = false) String actor) {
        String who = Actor.required(actor);
        return activation.activate(code, idempotencyKey, who, platform.today(), platform.calendar());
    }

    @PostMapping("/{code}/cancel")
    @Operation(summary = "Cancel a group before it starts and refund fees and deposits (chit.group.cancel)",
            description = "Allowed in DRAFT, OPEN_FOR_ENROLMENT and FULL. Needs a reason, Idempotency-Key and X-Actor.")
    public CancelResult cancel(@PathVariable String code, @RequestBody CancelRequest request,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestHeader(value = "X-Actor", required = false) String actor) {
        String who = Actor.required(actor);
        return enrolment.cancel(code, request, idempotencyKey, who, platform.today());
    }

    private static GroupStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return GroupStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ChitException.badRequest("VALIDATION_FAILED", "status must be one of " + java.util.Arrays.toString(GroupStatus.values()));
        }
    }
}
