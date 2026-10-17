package com.bank.lending.accounting.service;

import com.bank.lending.accounting.dto.AccountingDtos.EventView;
import com.bank.lending.accounting.dto.AccountingDtos.ExceptionRow;
import com.bank.lending.accounting.dto.AccountingDtos.GateStatus;
import com.bank.lending.accounting.dto.AccountingDtos.GlAccountView;
import com.bank.lending.accounting.dto.AccountingDtos.JournalSummary;
import com.bank.lending.accounting.dto.AccountingDtos.JournalView;
import com.bank.lending.accounting.dto.AccountingDtos.LedgerView;
import com.bank.lending.accounting.dto.AccountingDtos.RouteView;
import com.bank.lending.accounting.dto.AccountingDtos.SimulatedLine;
import com.bank.lending.accounting.dto.AccountingDtos.SimulationRequest;
import com.bank.lending.accounting.dto.AccountingDtos.SimulationResult;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateView;
import com.bank.lending.accounting.dto.AccountingDtos.TrialBalance;
import com.bank.lending.accounting.dto.AccountingDtos.TrialBalanceRow;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.engine.JournalAssembler;
import com.bank.lending.accounting.engine.TemplateSelector;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import com.bank.lending.accounting.model.PostingException;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.ConfigAdminRepository;
import com.bank.lending.accounting.repository.ExceptionRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read side of the accounting module: journals, balances, configuration views, exceptions, and the simulator. */
@Service
@Transactional(readOnly = true)
public class AccountingQueryService {

    private static final int MAX_PAGE = 500;

    private final JournalRepository journals;
    private final ConfigAdminRepository admin;
    private final AccountingConfigRepository configRepository;
    private final ExceptionRepository exceptions;
    private final BusinessDateService businessDates;

    public AccountingQueryService(JournalRepository journals, ConfigAdminRepository admin,
                                  AccountingConfigRepository configRepository, ExceptionRepository exceptions,
                                  BusinessDateService businessDates) {
        this.journals = journals;
        this.admin = admin;
        this.configRepository = configRepository;
        this.exceptions = exceptions;
        this.businessDates = businessDates;
    }

    // ------------------------------------------------------------------------------------------------ journals

    public List<JournalSummary> searchJournals(Long accountId, String eventCode, String journalNo, LocalDate from,
                                               LocalDate to, String branchCode, int limit, int offset) {
        return journals.search(accountId, eventCode, journalNo, from, to, branchCode, Math.max(1, Math.min(limit, MAX_PAGE)),
                Math.max(0, offset));
    }

    public JournalView journal(long journalId) {
        JournalSummary h = journals.find(journalId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal " + journalId + " not found"));
        return new JournalView(h, journals.lines(journalId, h.businessDate()), journals.reversedBy(journalId).orElse(null));
    }

    /** Debits, credits and net per GL account up to a date (default: the business date). */
    public TrialBalance trialBalance(LocalDate asOf, Integer ledgerId, String branchCode) {
        LocalDate date = asOf == null ? businessDates.currentDate() : asOf;
        List<TrialBalanceRow> rows = journals.trialBalance(date, ledgerId, branchCode);
        BigDecimal debits = rows.stream().map(TrialBalanceRow::debits).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = rows.stream().map(TrialBalanceRow::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TrialBalance(date, rows, debits, credits, debits.compareTo(credits) == 0);
    }

    // ------------------------------------------------------------------------------------------------ configuration

    public List<EventView> events() {
        return admin.events();
    }

    public List<LedgerView> ledgers() {
        return admin.ledgers();
    }

    public List<GlAccountView> accounts(int ledgerId) {
        return admin.accounts(ledgerId);
    }

    public List<RouteView> routes(Integer ledgerId, String status) {
        return admin.routes(ledgerId, status);
    }

    public List<TemplateView> templates(String eventCode, String status) {
        return admin.templates(eventCode, status);
    }

    public TemplateView template(long templateId) {
        return admin.template(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template " + templateId + " not found"));
    }

    // ------------------------------------------------------------------------------------------------ exceptions

    public List<ExceptionRow> exceptions(String status, int limit, int offset) {
        return exceptions.list(status, Math.max(1, Math.min(limit, MAX_PAGE)), Math.max(0, offset));
    }

    /** Open exceptions, and how many of them hold up end of day (the gate the batch service checks). */
    public GateStatus gate() {
        return new GateStatus(exceptions.countOpen(true), exceptions.countOpen(false));
    }

    // ------------------------------------------------------------------------------------------------ simulator

    /**
     * Shows the journal an event would produce, without writing anything. Uses the given template (any status, so a
     * draft can be tried before it is submitted) or the one the engine would select. Failures come back as a message,
     * not an error, because that is the point of trying.
     */
    public SimulationResult simulate(SimulationRequest r) {
        AccountingConfig cfg = configRepository.current();
        EventDef ev = cfg.events().get(r.eventCode());
        if (ev == null) {
            throw new ResourceNotFoundException("Unknown accounting event " + r.eventCode());
        }
        LocalDate date = r.businessDate() == null ? businessDates.currentDate() : r.businessDate();
        TemplateDef tpl;
        if (r.templateId() != null) {
            tpl = configRepository.template(r.templateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Template " + r.templateId() + " not found"));
            if (!tpl.eventCode().equals(ev.eventCode())) {
                throw new IllegalArgumentException("Template " + r.templateId() + " belongs to event " + tpl.eventCode());
            }
        } else {
            tpl = TemplateSelector.select(cfg.templatesByEvent().get(ev.eventCode()), r.productCode(), date).orElse(null);
            if (tpl == null) {
                return new SimulationResult(ev.eventCode(), null, null, List.of(), false,
                        "No effective approved template for " + ev.eventCode() + " on " + date);
            }
        }
        Map<String, Object> ctx = new HashMap<>(r.context() == null ? Map.of() : r.context());
        if (r.npaStage() != null) {
            ctx.put("npa_stage", r.npaStage());
        }
        String currency = r.currency() == null ? "INR" : r.currency();
        PostingRequest req = new PostingRequest(ev.eventCode(), 0L, r.productCode(), "SIMULATION", 0L, date, null, date,
                date, currency, r.amounts() == null ? Map.of() : r.amounts(), ctx, List.of(), "SIMULATION",
                r.branchCode() == null || r.branchCode().isBlank() ? PostingRequest.DEFAULT_BRANCH : r.branchCode().trim().toUpperCase());
        try {
            DraftJournal draft = JournalAssembler.assemble(cfg, req, ev, tpl, JournalAssembler.SAME_CURRENCY_ONLY);
            if (draft == null) {
                return new SimulationResult(ev.eventCode(), tpl.templateId(), tpl.version(), List.of(), true,
                        "Every amount is zero, so no journal would be written");
            }
            List<SimulatedLine> lines = draft.lines().stream().map(l -> new SimulatedLine(l.lineSeq(), l.ledgerCode(),
                    l.glAccountCode(), l.roleCode(), l.side(), l.amountTxn(), l.currencyTxn(), l.fxRate(),
                    l.amountBase(), l.currencyBase(), l.ruleId() == 0L ? null : l.ruleId())).toList();
            return new SimulationResult(ev.eventCode(), tpl.templateId(), tpl.version(), lines, true, null);
        } catch (PostingException e) {
            return new SimulationResult(ev.eventCode(), tpl.templateId(), tpl.version(), List.of(), false,
                    e.reason() + ": " + e.getMessage());
        }
    }
}
