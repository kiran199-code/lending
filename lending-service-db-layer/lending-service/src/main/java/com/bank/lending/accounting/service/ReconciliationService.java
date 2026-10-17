package com.bank.lending.accounting.service;

import com.bank.lending.accounting.AccountingSettings;
import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationResult;
import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationRow;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.ExceptionRepository;
import com.bank.lending.accounting.repository.ReconciliationRepository;
import com.bank.lending.accounting.repository.SubledgerRepository;
import com.bank.lending.accounting.repository.SubledgerRepository.Position;
import com.bank.lending.businessdate.service.BusinessDateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Proves the general ledger agrees with the loan sub-ledger, and that every ledger balances. Run at the end of day,
 * it compares, account by account, what the journals say is owed in each bucket with what the demand, accrual and
 * account tables say. A bucket passes only when no account differs beyond its tolerance (nothing for principal,
 * due interest, penal interest and advances; a paisa for accrued interest, see {@code AccountingSettings}).
 *
 * <p>The sub-ledger is read as it stands now, so a run is only accepted for the current business date; earlier
 * results are kept and can be read back.
 */
@Service
public class ReconciliationService {

    private static final int MAX_DETAIL_ACCOUNTS = 5;

    private record Bucket(String check, String role, boolean creditNature, Function<Position, BigDecimal> subledger,
                          boolean interestTolerance) {
    }

    private static final List<Bucket> BUCKETS = List.of(
            new Bucket("RECON_PRINCIPAL_NOT_DUE", "LOAN_PRINCIPAL", false, Position::principalNotDue, false),
            new Bucket("RECON_PRINCIPAL_DUE", "PRINCIPAL_DUE", false, Position::principalDue, false),
            new Bucket("RECON_INTEREST_ACCRUED", "INT_ACCRUED_RECEIVABLE", false, Position::interestAccrued, true),
            new Bucket("RECON_INTEREST_DUE", "INT_DUE_RECEIVABLE", false, Position::interestDue, false),
            new Bucket("RECON_PENAL", "PENAL_RECEIVABLE", false, Position::penal, false),
            new Bucket("RECON_ADVANCE", "ADVANCE_LIABILITY", true, Position::advance, false));

    private final ReconciliationRepository repository;
    private final SubledgerRepository subledger;
    private final AccountingConfigRepository configRepository;
    private final ExceptionRepository exceptions;
    private final AccountingSettings settings;
    private final BusinessDateService businessDates;

    public ReconciliationService(ReconciliationRepository repository, SubledgerRepository subledger,
                                 AccountingConfigRepository configRepository, ExceptionRepository exceptions,
                                 AccountingSettings settings, BusinessDateService businessDates) {
        this.repository = repository;
        this.subledger = subledger;
        this.configRepository = configRepository;
        this.exceptions = exceptions;
        this.settings = settings;
        this.businessDates = businessDates;
    }

    @Transactional
    public ReconciliationResult run(LocalDate requestedDate) {
        LocalDate current = businessDates.currentDate();
        LocalDate date = requestedDate == null ? current : requestedDate;
        if (!date.equals(current)) {
            throw new IllegalArgumentException("Reconciliation compares the ledger with the loan sub-ledger as it stands now, "
                    + "so it can only be run for the current business date (" + current + "), not " + date);
        }
        if (!settings.enabled()) {
            // Nothing is journalled while accounting is off, so there is nothing to prove; do not report every account as a break.
            return new ReconciliationResult(date, true, List.of(new ReconciliationRow("ACCOUNTING_DISABLED", "PASS",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Accounting is switched off (accounting.enabled=false); nothing was reconciled", date, null)));
        }
        List<ReconciliationRow> rows = new ArrayList<>();

        // 1. every ledger balances in every currency
        BigDecimal debitsAll = BigDecimal.ZERO;
        BigDecimal creditsAll = BigDecimal.ZERO;
        List<String> unbalanced = new ArrayList<>();
        for (Object[] t : repository.ledgerTotals(date)) {
            BigDecimal dr = (BigDecimal) t[2];
            BigDecimal cr = (BigDecimal) t[3];
            debitsAll = debitsAll.add(dr);
            creditsAll = creditsAll.add(cr);
            if (dr.compareTo(cr) != 0) {
                unbalanced.add("ledger " + t[0] + " " + t[1] + " branch " + t[4] + ": DR " + dr + " vs CR " + cr);
            }
        }
        rows.add(record(date, "LEDGER_BALANCED", unbalanced.isEmpty(), debitsAll, creditsAll,
                unbalanced.isEmpty() ? "Every ledger balances in every currency and branch" : String.join("; ", unbalanced)));

        // 2. no event is stuck waiting in the exception queue
        long openGate = exceptions.countOpen(true);
        rows.add(record(date, "NO_OPEN_GATE_EXCEPTIONS", openGate == 0, BigDecimal.ZERO, BigDecimal.valueOf(openGate),
                openGate == 0 ? "No end-of-day-gating posting exceptions are open"
                        : openGate + " posting exception(s) that gate end of day are still open"));

        // 3. every entry is in the currency and branch of its account
        List<Long> wrong = repository.accountsPostedInWrongCurrencyOrBranch();
        rows.add(record(date, "RECON_ACCOUNT_DIMENSIONS", wrong.isEmpty(), BigDecimal.ZERO, BigDecimal.valueOf(wrong.size()),
                wrong.isEmpty() ? "Every journal uses the currency and branch of its account"
                        : wrong.size() + " account(s) have journals in another currency or branch than their own: "
                        + wrong.stream().limit(MAX_DETAIL_ACCOUNTS).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))));

        // 4. the loan buckets, account by account
        LedgerDef primary = configRepository.primaryLedger().orElse(null);
        if (primary == null) {
            rows.add(record(date, "RECON_PRIMARY_LEDGER", false, BigDecimal.ZERO, BigDecimal.ZERO,
                    "No active internal primary ledger is configured"));
        } else {
            List<Position> positions = subledger.positions(null);
            Map<Long, Position> byAccount = new HashMap<>();
            positions.forEach(p -> byAccount.put(p.accountId(), p));
            Map<Long, Map<String, BigDecimal>> gl = repository.netDebitByAccountAndRole(primary.ledgerId(), date,
                    BUCKETS.stream().map(Bucket::role).toList());
            TreeSet<Long> accountIds = new TreeSet<>(byAccount.keySet());
            accountIds.addAll(gl.keySet());
            for (Bucket b : BUCKETS) {
                rows.add(reconcileBucket(date, b, accountIds, byAccount, gl));
            }
        }
        boolean all = rows.stream().allMatch(r -> "PASS".equals(r.status()));
        return new ReconciliationResult(date, all, rows);
    }

    @Transactional(readOnly = true)
    public ReconciliationResult latest(LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? businessDates.currentDate() : requestedDate;
        List<ReconciliationRow> rows = repository.latest(date);
        return new ReconciliationResult(date, !rows.isEmpty() && rows.stream().allMatch(r -> "PASS".equals(r.status())), rows);
    }

    private ReconciliationRow reconcileBucket(LocalDate date, Bucket b, TreeSet<Long> accountIds,
                                              Map<Long, Position> byAccount, Map<Long, Map<String, BigDecimal>> gl) {
        BigDecimal tolerance = b.interestTolerance() ? settings.interestTolerance() : BigDecimal.ZERO;
        BigDecimal glTotal = BigDecimal.ZERO;
        BigDecimal subTotal = BigDecimal.ZERO;
        int differing = 0;
        List<String> samples = new ArrayList<>();
        for (Long id : accountIds) {
            Position p = byAccount.get(id);
            BigDecimal sub = p == null ? BigDecimal.ZERO : b.subledger().apply(p);
            BigDecimal net = gl.getOrDefault(id, Map.of()).getOrDefault(b.role(), BigDecimal.ZERO);
            BigDecimal glValue = b.creditNature() ? net.negate() : net;
            glTotal = glTotal.add(glValue);
            subTotal = subTotal.add(sub);
            if (glValue.subtract(sub).abs().compareTo(tolerance) > 0) {
                differing++;
                if (samples.size() < MAX_DETAIL_ACCOUNTS) {
                    samples.add("account " + id + ": ledger " + glValue.toPlainString() + " vs sub-ledger " + sub.toPlainString());
                }
            }
        }
        boolean pass = differing == 0;
        String detail = pass ? "All accounts agree" : differing + " account(s) differ; " + String.join("; ", samples);
        return record(date, b.check(), pass, subTotal, glTotal, detail);
    }

    private ReconciliationRow record(LocalDate date, String check, boolean pass, BigDecimal expected, BigDecimal actual,
                                     String detail) {
        BigDecimal diff = actual.subtract(expected);
        repository.save(date, check, pass, expected, actual, diff, detail);
        return new ReconciliationRow(check, pass ? "PASS" : "FAIL", expected, actual, diff, detail, date, null);
    }
}
