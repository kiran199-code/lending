package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Hashing;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.ActivationService.Plan;
import com.bank.lending.chit.enrolment.EnrolmentDtos.ActivationResult;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.group.GroupDtos.CycleView;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupRepository;
import com.bank.lending.chit.group.GroupService;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyStore;
import com.bank.lending.chit.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** The transactional half of activation. See {@link ActivationService} for why it is a separate bean. */
@Service
public class ActivationWriter {

    private static final String OPERATION = "GROUP_ACTIVATE";

    private final GroupService groupService;
    private final GroupRepository groups;
    private final EnrolmentRepository repo;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;

    public ActivationWriter(GroupService groupService, GroupRepository groups, EnrolmentRepository repo, LedgerService ledger,
                            IdempotencyStore idempotency, Audit audit) {
        this.groupService = groupService;
        this.groups = groups;
        this.repo = repo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    /** The answer to a repeated activation request, or null if this key has not completed one. */
    @Transactional(readOnly = true)
    public ActivationResult replayOf(String code, String idemKey) {
        Replay replay = idempotency.peek(idemKey, OPERATION, Hashing.sha256(code));
        if (replay == null) {
            return null;
        }
        return result(groupService.require(code)).asReplay();
    }

    @Transactional
    public ActivationResult write(Plan plan, String idemKey, String actor, LocalDate today) {
        String code = plan.groupCode();
        Replay replay = idempotency.begin(idemKey, OPERATION, Hashing.sha256(code));
        if (replay != null) {
            return result(groupService.require(code)).asReplay();
        }
        GroupRow g = groupService.lock(code);
        if (g.version() != plan.groupVersion() || g.currentRulesVersion() != plan.rulesVersion()) {
            throw ChitException.conflict("DATA_CONFLICT", "Group " + code + " changed while the calendar was being prepared; nothing was written, repeat the request");
        }
        ChitRules rules = groupService.rulesOf(g);
        // Everything is checked again under the lock, now including the first due date against the business date.
        ActivationCheck.requireClear(new ActivationCheck.Input(g.status(), rules, ActivationStates.of(repo, g.id()), today,
                plan.cycles().get(0).dueDate()));
        Lifecycles.GROUP.require(g.status(), GroupStatus.ACTIVE);

        List<TicketRow> tickets = repo.tickets(g.id(), false);
        groups.freezeRulesVersion(g.id(), g.currentRulesVersion());
        repo.activateTickets(g.id(), actor);
        plan.cycles().forEach(c -> groups.insertCycle(g.id(), c));
        int accounts = ledger.ensureGroupAccounts(g.id(), tickets.stream().map(TicketRow::id).toList());
        groups.markActivated(g.id(), actor);

        audit.log("GROUP", code, "ACTIVATED", actor, "rules version " + g.currentRulesVersion() + " frozen, " + plan.cycles().size() + " cycles");
        audit.event("ChitGroupActivated", "GROUP", code, Json.obj("groupCode", code, "rulesVersion", g.currentRulesVersion(),
                "tickets", tickets.size(), "cycles", plan.cycles().size(), "firstDueDate", plan.cycles().get(0).dueDate().toString(),
                "lastAuctionDate", plan.cycles().get(plan.cycles().size() - 1).auctionDate().toString(),
                "ledgerAccounts", accounts, "actor", actor));
        idempotency.complete(idemKey, code);
        return result(groupService.require(code));
    }

    private ActivationResult result(GroupRow g) {
        List<CycleView> cycles = groups.cycles(g.id()).stream().map(c -> new CycleView(c.cycleNo(), c.status(), c.dueDate(), c.auctionDate())).toList();
        return new ActivationResult(groupService.viewOf(g), cycles, null);
    }
}
