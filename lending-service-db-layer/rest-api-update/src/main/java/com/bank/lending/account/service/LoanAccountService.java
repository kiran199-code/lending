package com.bank.lending.account.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.account.util.AccountNumberGenerator;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import com.bank.lending.ledger.service.LedgerOutboxService;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.service.AmortizationScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bank.lending.account.domain.LoanAccount.AccountStatus.*;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PENDING;

@Service
@RequiredArgsConstructor
public class LoanAccountService {

    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    private static final Set<LoanAccount.AccountStatus> DELINQUENCY_TRACKED_STATUSES = EnumSet.of(
            ACTIVE, NPA_SMA0, NPA_SMA1, NPA_SMA2, NPA_SUBSTANDARD, NPA_DOUBTFUL, NPA_LOSS);

    private final LoanAccountRepository loanAccountRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final AmortizationScheduleService scheduleService;
    private final AccountNumberGenerator accountNumberGenerator;
    private final LedgerOutboxService ledgerOutboxService;

    /** Idempotent on sanction_ref_id: a retried creation request for an already-created sanction returns the existing account. */
    @Transactional
    public LoanAccount createAccount(CreateLoanAccountRequest request) {
        Optional<LoanAccount> existing = loanAccountRepository.findBySanctionRefId(request.sanctionRefId());
        if (existing.isPresent()) {
            return existing.get();
        }

        LoanAccount account = LoanAccount.builder()
                .customerId(request.customerId())
                .sanctionRefId(request.sanctionRefId())
                .productCode(request.productCode())
                .accountType(request.accountType())
                .sanctionedAmount(request.sanctionedAmount())
                .tenureMonths(request.tenureMonths())
                .rateType(request.rateType())
                .interestRate(request.interestRate())
                .benchmarkRateRef(request.benchmarkRateRef())
                .spread(request.spread())
                .dayCountConvention(request.dayCountConvention() != null
                        ? request.dayCountConvention() : LoanAccount.DayCountConvention.ACT_365)
                .accountStatus(PENDING_DISBURSEMENT)
                .version(0)
                .build();

        return persistWithGeneratedAccountNumber(account);
    }

    private LoanAccount persistWithGeneratedAccountNumber(LoanAccount account) {
        DataIntegrityViolationException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            account.setAccountNumber(accountNumberGenerator.next(account.getProductCode()));
            try {
                return loanAccountRepository.saveAndFlush(account);
            } catch (DataIntegrityViolationException e) {
                lastFailure = e;
            }
        }
        throw lastFailure;
    }

    @Transactional(readOnly = true)
    public LoanAccount getById(Long accountId) {
        return loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
    }

    @Transactional(readOnly = true)
    public LoanAccount getByAccountNumber(String accountNumber) {
        return loanAccountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountNumber + " not found"));
    }

    /** Paged loan accounts for a customer — backs the customer-facing "my loans" listing. */
    @Transactional(readOnly = true)
    public Page<LoanAccount> findByCustomerId(Long customerId, Pageable pageable) {
        return loanAccountRepository.findByCustomerId(customerId, pageable);
    }

    /**
     * Disburses a sanctioned account and generates its initial (version 1) amortization schedule.
     * Idempotent: replaying against an already-ACTIVE account returns it unchanged.
     */
    @Transactional
    public LoanAccount disburse(Long accountId, BigDecimal disbursedAmount, LocalDate firstEmiDueDate) {
        LoanAccount account = getById(accountId);

        if (account.getAccountStatus() == ACTIVE) {
            return account;
        }
        if (account.getAccountStatus() != PENDING_DISBURSEMENT) {
            throw new InvalidAccountStateException(
                    "Account " + accountId + " is not pending disbursement (status=" + account.getAccountStatus() + ")");
        }
        if (account.getAccountType() == LoanAccount.AccountType.MORTGAGE && account.getMortgageId() == null) {
            throw new InvalidAccountStateException(
                    "Mortgage account " + accountId + " has no registered mortgage charge linked yet");
        }

        account.setDisbursedAmount(disbursedAmount);
        account.setEmiStartDate(firstEmiDueDate);
        account.setAccountStatus(ACTIVE);
        LoanAccount saved = loanAccountRepository.save(account);

        scheduleService.generateInitialSchedule(saved, disbursedAmount, firstEmiDueDate);

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.DISBURSEMENT, Map.of(
                "disbursedAmount", disbursedAmount,
                "firstEmiDueDate", firstEmiDueDate.toString(),
                "tenureMonths", account.getTenureMonths()
        ));

        return saved;
    }

    /**
     * Applies a benchmark rate change to all active floating-rate accounts referencing it,
     * regenerating the remaining amortization schedule (same remaining tenure, recalculated EMI)
     * for each affected account.
     */
    @Transactional
    public void applyFloatingRateReset(String benchmarkRateRef, BigDecimal newBenchmarkRate) {
        int pageNo = 0;
        Page<LoanAccount> page;
        do {
            page = loanAccountRepository.findActiveFloatingByBenchmark(benchmarkRateRef, PageRequest.of(pageNo, 200));
            for (LoanAccount account : page.getContent()) {
                resetRateForAccount(account, newBenchmarkRate);
            }
            pageNo++;
        } while (page.hasNext());
    }

    private void resetRateForAccount(LoanAccount account, BigDecimal newBenchmarkRate) {
        BigDecimal spread = account.getSpread() != null ? account.getSpread() : BigDecimal.ZERO;
        BigDecimal newRate = newBenchmarkRate.add(spread);
        if (newRate.compareTo(account.getInterestRate()) == 0) {
            return;
        }

        account.setInterestRate(newRate);
        loanAccountRepository.save(account);

        int version = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0);
        if (version == 0) {
            return;
        }

        List<AmortizationScheduleEntry> pending = scheduleRepository
                .findByAccountIdAndScheduleVersionAndInstallmentStatus(account.getAccountId(), version, PENDING)
                .stream()
                .sorted(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .toList();
        if (pending.isEmpty()) {
            return;
        }

        AmortizationScheduleEntry nextPending = pending.get(0);
        BigDecimal outstanding = nextPending.getOutstandingBalance().add(nextPending.getPrincipalComponent());
        scheduleService.regenerateSchedule(account, outstanding, newRate, pending.size(),
                nextPending.getDueDate(), nextPending.getInstallmentNumber());
    }

    /**
     * Batch job: re-derives each tracked account's status from its oldest unpaid installment's
     * days-past-due, approximating RBI IRAC asset-classification bands (SMA-0/1/2 within 90 DPD,
     * NPA sub-standard/doubtful/loss beyond that). This is a simplification for illustrative
     * purposes — actual "loss asset" classification is a risk/audit judgment, not purely
     * day-count driven, so treat the >1095-day NPA_LOSS threshold here as a placeholder to
     * revisit with Risk/Compliance before relying on it operationally.
     */
    @Transactional
    public void classifyDelinquency(LocalDate asOfDate) {
        int pageNo = 0;
        Page<LoanAccount> page;
        do {
            page = loanAccountRepository.findByAccountStatusIn(DELINQUENCY_TRACKED_STATUSES, PageRequest.of(pageNo, 200));
            for (LoanAccount account : page.getContent()) {
                classifyAccount(account, asOfDate);
            }
            pageNo++;
        } while (page.hasNext());
    }

    private void classifyAccount(LoanAccount account, LocalDate asOfDate) {
        int version = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0);
        if (version == 0) {
            return;
        }

        List<AmortizationScheduleEntry> entries = scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(account.getAccountId(), version);

        Optional<AmortizationScheduleEntry> oldestUnpaid = entries.stream()
                .filter(e -> e.getInstallmentStatus() != AmortizationScheduleEntry.InstallmentStatus.PAID
                        && !e.getDueDate().isAfter(asOfDate))
                .min(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber));

        if (oldestUnpaid.isEmpty()) {
            if (account.getAccountStatus() != ACTIVE) {
                account.setAccountStatus(ACTIVE);
                loanAccountRepository.save(account);
            }
            return;
        }

        long daysPastDue = ChronoUnit.DAYS.between(oldestUnpaid.get().getDueDate(), asOfDate);
        LoanAccount.AccountStatus newStatus = classifyByDaysPastDue(daysPastDue);
        if (newStatus != account.getAccountStatus()) {
            account.setAccountStatus(newStatus);
            loanAccountRepository.save(account);
        }

        if (oldestUnpaid.get().getInstallmentStatus() == PENDING && daysPastDue > 0) {
            oldestUnpaid.get().setInstallmentStatus(AmortizationScheduleEntry.InstallmentStatus.OVERDUE);
            scheduleRepository.save(oldestUnpaid.get());
        }
    }

    private LoanAccount.AccountStatus classifyByDaysPastDue(long daysPastDue) {
        if (daysPastDue <= 0) return ACTIVE;
        if (daysPastDue <= 30) return NPA_SMA0;
        if (daysPastDue <= 60) return NPA_SMA1;
        if (daysPastDue <= 90) return NPA_SMA2;
        if (daysPastDue <= 365) return NPA_SUBSTANDARD;
        if (daysPastDue <= 1095) return NPA_DOUBTFUL;
        return NPA_LOSS;
    }
}
