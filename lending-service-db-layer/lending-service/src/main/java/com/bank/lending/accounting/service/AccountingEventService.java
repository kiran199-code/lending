package com.bank.lending.accounting.service;

import com.bank.lending.accounting.AccountingSettings;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.engine.JournalAssembler;
import com.bank.lending.accounting.engine.RoundingCarry;
import com.bank.lending.accounting.engine.TemplateSelector;
import com.bank.lending.accounting.model.Defs.CarryInput;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import com.bank.lending.accounting.model.PostingException;
import com.bank.lending.accounting.model.PostingException.Reason;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.CarryRepository;
import com.bank.lending.accounting.repository.ExceptionRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The single place where journals are generated (requirement: one engine, no other code writes the journal tables).
 *
 * <p>Business modules call {@link #post} (or {@link #postSafely} for events configured non-blocking) from inside their
 * own transaction, so the journal commits or rolls back with the business change. Steps: validate, check the period,
 * pick the template, claim the idempotency key, apply any rounding carry, assemble the balanced journal, store it.
 */
@Service
public class AccountingEventService {

    private static final Logger log = LoggerFactory.getLogger(AccountingEventService.class);

    private final AccountingConfigRepository configRepository;
    private final JournalRepository journals;
    private final CarryRepository carries;
    private final ExceptionRepository exceptions;
    private final AccountingSettings settings;
    private final ObjectMapper mapper;
    private final TransactionTemplate nested;

    public AccountingEventService(AccountingConfigRepository configRepository, JournalRepository journals,
                                  CarryRepository carries, ExceptionRepository exceptions, AccountingSettings settings,
                                  ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.configRepository = configRepository;
        this.journals = journals;
        this.carries = carries;
        this.exceptions = exceptions;
        this.settings = settings;
        this.mapper = mapper;
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** Posts an event; any failure propagates and rolls the caller's transaction back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult post(PostingRequest request) {
        return doPost(request);
    }

    /**
     * Posts an event honouring its blocking mode: a BLOCKING event behaves like {@link #post}; a NON_BLOCKING one that
     * fails is rolled back to a savepoint and written to the exception queue, and the caller carries on.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult postSafely(PostingRequest request) {
        EventDef ev = configRepository.current().events().get(request.eventCode());
        if (ev == null || ev.blocking()) {
            return doPost(request);
        }
        try {
            return runNested(() -> doPost(request));
        } catch (RuntimeException e) {
            recordFailure(request, ev, e);
            return PostResult.failedRecorded(e.getMessage());
        }
    }

    /** Re-submits an open exception. Success resolves it; failure counts another attempt and leaves it open. */
    @Transactional
    public PostResult retryException(long exceptionId, String actor) {
        ExceptionRepository.Pending pending = exceptions.findOpen(exceptionId).orElseThrow(
                () -> new ResourceNotFoundException("No open posting exception " + exceptionId));
        PostingRequest request;
        try {
            request = mapper.readValue(pending.payload(), PostingRequest.class);
        } catch (JsonProcessingException e) {
            exceptions.markRetryFailed(exceptionId, Reason.INVALID_REQUEST.name(), "Stored payload cannot be read: " + e.getMessage());
            return PostResult.failedRecorded("Stored payload cannot be read");
        }
        try {
            PostResult result = runNested(() -> doPost(request));
            exceptions.resolve(exceptionId, "RESOLVED", actor, "Retried successfully: " + result.status());
            return result;
        } catch (RuntimeException e) {
            exceptions.markRetryFailed(exceptionId, reasonOf(e), e.getMessage());
            return PostResult.failedRecorded(e.getMessage());
        }
    }

    /** Closes an open exception without journalling it; the reason is mandatory and kept with the actor. */
    @Transactional
    public void waiveException(long exceptionId, String actor, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A reason is required to waive a posting exception");
        }
        exceptions.findOpen(exceptionId).orElseThrow(
                () -> new ResourceNotFoundException("No open posting exception " + exceptionId));
        exceptions.resolve(exceptionId, "WAIVED", actor, note);
    }

    /** Runs work in a savepoint of the caller's transaction, so a failure undoes only that work. A seam for tests. */
    protected <T> T runNested(Supplier<T> work) {
        return nested.execute(status -> work.get());
    }

    // ----------------------------------------------------------------------------------------------- the engine

    private PostResult doPost(PostingRequest req) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        AccountingConfig cfg = configRepository.current();
        EventDef ev = cfg.events().get(req.eventCode());
        if (ev == null) {
            throw new PostingException(Reason.UNKNOWN_EVENT, "Unknown accounting event " + req.eventCode());
        }
        if ("OFF".equals(ev.postingMode())) {
            return PostResult.skipped("event " + ev.eventCode() + " is OFF");
        }
        if (!ev.active()) {
            throw new PostingException(Reason.INACTIVE_EVENT, "Accounting event " + ev.eventCode() + " is inactive");
        }
        validate(req, ev);

        if ("CLOSED".equals(journals.periodStatus(req.businessDate()))) {
            throw new PostingException(Reason.PERIOD_CLOSED, "The accounting period of " + req.businessDate() + " is closed");
        }
        boolean priorPeriod = "CLOSED".equals(journals.periodStatus(req.valueDate()));
        boolean backdated = req.valueDate().isBefore(req.businessDate());

        TemplateDef template = TemplateSelector.select(cfg.templatesByEvent().get(ev.eventCode()), req.productCode(),
                req.businessDate()).orElse(null);
        if (template == null) {
            if ("SKIP_WITH_EXCEPTION".equals(ev.onMissingTemplate())) {
                recordFailure(req, ev, new PostingException(Reason.NO_TEMPLATE, "No effective template for " + ev.eventCode()));
                return PostResult.skipped("no effective template for " + ev.eventCode());
            }
            throw new PostingException(Reason.NO_TEMPLATE, "No effective entry template for event " + ev.eventCode()
                    + " (product " + req.productCode() + ", date " + req.businessDate() + ")");
        }

        String key = req.idempotencyKey();
        long journalId = journals.nextJournalId();
        if (!journals.claimKey(key, journalId, req.businessDate())) {
            return journals.findByKey(key)
                    .map(s -> PostResult.duplicate(s.journalId() == 0L ? null : s.journalId(),
                            s.journalId() == 0L ? null : s.journalNo()))
                    .orElseGet(() -> PostResult.duplicate(null, null));
        }

        // accrual amounts are posted as the change in their rounded cumulative
        Map<String, BigDecimal> amounts = new HashMap<>(req.amounts());
        List<Runnable> saveCarries = new ArrayList<>();
        int precision = JournalAssembler.precision(req.currency());
        for (CarryInput c : req.carries() == null ? List.<CarryInput>of() : req.carries()) {
            CarryRepository.State st = carries.lock(req.accountId(), c.stream(), c.refId());
            RoundingCarry.Step step = RoundingCarry.apply(st.cumulativeAccrued(), st.cumulativePosted(), c.raw(), precision);
            if (step.delta().signum() < 0) {
                throw new PostingException(Reason.INVALID_REQUEST, "Accrual stream " + c.stream() + " would post a negative amount");
            }
            amounts.merge(c.tag(), step.delta(), BigDecimal::add);
            long accountId = req.accountId();
            saveCarries.add(() -> carries.save(accountId, c.stream(), c.refId(), step.cumulativeAccrued(), step.cumulativePosted()));
        }
        PostingRequest effective = new PostingRequest(req.eventCode(), req.accountId(), req.productCode(),
                req.sourceType(), req.sourceId(), req.sourceDate(), req.qualifier(), req.businessDate(),
                req.valueDate(), req.currency(), amounts, req.context(), req.carries(), req.createdBy(), req.branchCode());

        DraftJournal draft = JournalAssembler.assemble(cfg, effective, ev, template, JournalAssembler.SAME_CURRENCY_ONLY);
        saveCarries.forEach(Runnable::run);
        if (draft == null) {
            journals.markNoJournal(key);
            return PostResult.zero();
        }
        String stage = req.context() == null || req.context().get("npa_stage") == null
                ? null : String.valueOf(req.context().get("npa_stage"));
        journals.insert(journalId, effective, draft, "SHADOW".equals(ev.postingMode()) ? "SHADOW" : "LIVE", backdated,
                priorPeriod, null, stage);
        return PostResult.posted(journalId, JournalRepository.journalNo(journalId));
    }

    private static void validate(PostingRequest req, EventDef ev) {
        if (req.sourceType() == null || req.businessDate() == null || req.valueDate() == null || req.currency() == null
                || req.amounts() == null) {
            throw new PostingException(Reason.INVALID_REQUEST, "Posting request for " + ev.eventCode()
                    + " lacks source type, dates, currency or amounts");
        }
        if (req.valueDate().isAfter(req.businessDate())) {
            throw new PostingException(Reason.INVALID_REQUEST, "Value date " + req.valueDate()
                    + " is after the business date " + req.businessDate());
        }
        if (req.accountId() == null) {
            throw new PostingException(Reason.INVALID_REQUEST, "Posting request for " + ev.eventCode() + " has no account");
        }
        Set<String> tags = ev.tags();
        for (Map.Entry<String, BigDecimal> a : req.amounts().entrySet()) {
            if (!tags.contains(a.getKey())) {
                throw new PostingException(Reason.INVALID_REQUEST, "Amount tag " + a.getKey() + " is not defined for event " + ev.eventCode());
            }
            if (a.getValue() == null || a.getValue().signum() < 0) {
                throw new PostingException(Reason.NEGATIVE_AMOUNT, "Amount tag " + a.getKey() + " is negative or missing");
            }
        }
        if (req.carries() != null) {
            for (CarryInput c : req.carries()) {
                if (!tags.contains(c.tag())) {
                    throw new PostingException(Reason.INVALID_REQUEST, "Amount tag " + c.tag() + " is not defined for event " + ev.eventCode());
                }
                if (c.raw() == null || c.raw().signum() < 0) {
                    throw new PostingException(Reason.NEGATIVE_AMOUNT, "Accrual " + c.stream() + " is negative or missing");
                }
            }
        }
    }

    private void recordFailure(PostingRequest req, EventDef ev, RuntimeException e) {
        log.warn("Accounting event {} for account {} could not be journalled: {}", req.eventCode(), req.accountId(), e.getMessage());
        String payload;
        try {
            payload = mapper.writeValueAsString(req);
        } catch (JsonProcessingException je) {
            payload = "{}";
        }
        exceptions.record(req.eventCode(), req.accountId(), req.idempotencyKey(), payload, reasonOf(e), e.getMessage(),
                ev.eodGate());
    }

    private static String reasonOf(RuntimeException e) {
        return e instanceof PostingException pe ? pe.reason().name() : "ERROR";
    }
}
