package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.DefaultDetector;
import com.bank.lending.chit.calc.DefaultDetector.OverdueItem;
import com.bank.lending.chit.calc.DefaultDetector.Verdict;
import com.bank.lending.chit.collection.CollectionModels.DemandRow;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.enrolment.EnrolmentRepository;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flags tickets that have breached the group's default threshold: ACTIVE or PRIZED tickets become DEFAULTED and a
 * default case is opened with the figures that justified it. Nothing is recovered or written off here (Phase 6); a
 * payment that clears the overdue amount cures the case (see {@link PaymentService}). Already-defaulted tickets are
 * left alone, so a rerun changes nothing.
 */
@Service
public class DefaultWriter {

    private final GroupService groupService;
    private final CollectionRepository repo;
    private final EnrolmentRepository tickets;
    private final Audit audit;

    public DefaultWriter(GroupService groupService, CollectionRepository repo, EnrolmentRepository tickets, Audit audit) {
        this.groupService = groupService;
        this.repo = repo;
        this.tickets = tickets;
        this.audit = audit;
    }

    @Transactional
    public Map<String, BigDecimal> detect(String groupCode, LocalDate today, String actor) {
        Map<String, BigDecimal> figures = new LinkedHashMap<>();
        figures.put("ticketsDefaulted", BigDecimal.ZERO);

        GroupRow g = groupService.lock(groupCode);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            return figures;
        }
        ChitRules rules = groupService.rulesOf(g);
        if (rules.defaultThreshold() == null) {
            return figures;
        }
        int grace = rules.graceDays() == null ? 0 : rules.graceDays();
        Map<Long, List<DemandRow>> byTicket = new TreeMap<>();
        for (DemandRow d : repo.openDemandsOfGroup(g.id())) {
            byTicket.computeIfAbsent(d.ticketId(), k -> new ArrayList<>()).add(d);
        }
        for (TicketRow t : tickets.tickets(g.id(), false)) {
            if (t.status() != TicketStatus.ACTIVE && t.status() != TicketStatus.PRIZED) {
                continue;
            }
            List<DemandRow> demands = byTicket.getOrDefault(t.id(), List.of());
            Verdict v = DefaultDetector.evaluate(rules.defaultThreshold(), grace, today,
                    demands.stream().map(d -> new OverdueItem(d.dueDate(), d.figures().subscriptionOutstanding())).toList());
            if (!v.breached()) {
                continue;
            }
            Lifecycles.TICKET.require(t.status(), TicketStatus.DEFAULTED);
            tickets.setTicketStatus(t.id(), TicketStatus.DEFAULTED, actor);
            BigDecimal shortfall = demands.stream().filter(d -> d.dueDate().isBefore(today))
                    .map(d -> d.figures().totalOutstanding()).reduce(Money.ZERO, BigDecimal::add);
            if (repo.openCase(t.id()).isEmpty()) {
                repo.insertCase(t.id(), today, shortfall, v.reason(), v.missedInstalments(), v.oldestDaysOverdue(), actor);
            }
            audit.log("TICKET", groupCode + "#" + t.ticketNo(), "DEFAULTED", actor, v.reason());
            audit.event("TicketDefaulted", "TICKET", groupCode + "/" + t.ticketNo(),
                    Json.obj("groupCode", groupCode, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "reason", v.reason(),
                            "missedInstalments", v.missedInstalments(), "daysOverdue", v.oldestDaysOverdue(), "shortfall", shortfall,
                            "previousStatus", t.status().name(), "businessDate", today.toString()));
            figures.merge("ticketsDefaulted", BigDecimal.ONE, BigDecimal::add);
        }
        return figures;
    }
}
