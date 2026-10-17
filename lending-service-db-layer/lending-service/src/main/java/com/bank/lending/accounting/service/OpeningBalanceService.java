package com.bank.lending.accounting.service;

import com.bank.lending.accounting.dto.AccountingDtos.OpeningBalanceResult;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.AccountingSettings;
import com.bank.lending.accounting.repository.CarryRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.accounting.repository.SubledgerRepository;
import com.bank.lending.accounting.repository.SubledgerRepository.Position;
import com.bank.lending.businessdate.service.BusinessDateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cutover: journals the position every existing loan account holds today as one OPENING_BALANCE entry against the
 * opening-balance contra account, and starts each account's interest and penal rounding carries from what the
 * sub-ledger already accrued. After it runs, daily events post on top and the reconciliation holds from day one.
 *
 * <p>Safe to repeat: an account that already has its opening entry is skipped, and so is any account that already has
 * other journals (opening a live account would double count). Each account is its own transaction, so one failure
 * does not undo the rest.
 */
@Service
public class OpeningBalanceService {

    private static final Logger log = LoggerFactory.getLogger(OpeningBalanceService.class);

    private final SubledgerRepository subledger;
    private final AccountingEventService events;
    private final CarryRepository carries;
    private final JournalRepository journals;
    private final AccountingSettings settings;
    private final BusinessDateService businessDates;
    private final TransactionTemplate tx;

    public OpeningBalanceService(SubledgerRepository subledger, AccountingEventService events, CarryRepository carries,
                                 JournalRepository journals, AccountingSettings settings,
                                 BusinessDateService businessDates, PlatformTransactionManager transactionManager) {
        this.subledger = subledger;
        this.events = events;
        this.carries = carries;
        this.journals = journals;
        this.settings = settings;
        this.businessDates = businessDates;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public OpeningBalanceResult run(String actor) {
        LocalDate date = businessDates.currentDate();
        int posted = 0;
        int skipped = 0;
        int failed = 0;
        for (Position p : subledger.positions(null)) {
            try {
                Boolean done = tx.execute(status -> open(p, date, actor));
                if (Boolean.TRUE.equals(done)) {
                    posted++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Opening balance for account {} failed: {}", p.accountId(), e.getMessage());
            }
        }
        return new OpeningBalanceResult(date, posted, skipped, failed);
    }

    private boolean open(Position p, LocalDate date, String actor) {
        BigDecimal assets = p.principalNotDue().add(p.principalDue()).add(p.interestAccrued()).add(p.interestDue())
                .add(p.penal());
        if (assets.signum() == 0 && p.advance().signum() == 0) {
            return false;
        }
        if (journals.countForAccount(p.accountId()) > 0) {
            return false; // already opened, or already has live postings
        }
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("OPENING_PRINCIPAL", p.principalNotDue());
        tags.put("OPENING_PRINCIPAL_DUE", p.principalDue());
        tags.put("OPENING_INT_ACCRUED", p.interestAccrued());
        tags.put("OPENING_INT_DUE", p.interestDue());
        tags.put("OPENING_PENAL", p.penal());
        tags.put("OPENING_ADVANCE", p.advance());
        BigDecimal net = assets.subtract(p.advance());
        tags.put("OPENING_NET", net.signum() > 0 ? net : BigDecimal.ZERO);
        tags.put("OPENING_NET_NEG", net.signum() < 0 ? net.negate() : BigDecimal.ZERO);
        tags.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("npa_stage", p.status());
        ctx.put("product_code", p.productCode());
        ctx.put("branch_code", p.branchCode());
        ctx.put("currency_code", p.currencyCode());
        PostingRequest req = new PostingRequest("OPENING_BALANCE", p.accountId(), p.productCode(), "ACCOUNT",
                p.accountId(), date, "OPENING", date, date, p.currencyCode(), tags, ctx, List.of(),
                actor == null ? "SYSTEM" : actor, p.branchCode());
        PostResult result = events.post(req);
        if (!result.journalled()) {
            return false;
        }
        startCarries(p.accountId());
        return true;
    }

    /** Interest and penal carries start from what the sub-ledger has accrued, so the next daily posting is a true delta. */
    private void startCarries(long accountId) {
        BigDecimal accrued = subledger.accrualTotal(accountId);
        if (accrued.signum() != 0) {
            carries.set(accountId, "INT", 0L, accrued, accrued.setScale(2, RoundingMode.HALF_UP));
        }
        for (BigDecimal[] d : subledger.penalByDemand(accountId)) {
            carries.set(accountId, "PENAL", d[0].longValue(), d[1], d[1].setScale(2, RoundingMode.HALF_UP));
        }
    }
}
