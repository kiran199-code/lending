package com.bank.lending.account.service;

import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.account.dto.DelinquencyResultResponse;
import com.bank.lending.account.dto.RateResetResultResponse;
import com.bank.lending.benchmark.domain.BenchmarkRate;
import com.bank.lending.benchmark.service.BenchmarkRateService;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.account.util.AccountNumberGenerator;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import com.bank.lending.ledger.service.LedgerOutboxService;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.AccountTermsResolver;
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
    private final AccountTermsResolver termsResolver;
    private final LoanProductRepository productRepository;
    private final BenchmarkRateService benchmarkRateService;
    private final BusinessDateService businessDateService;
    private final LoanDemandRepository demandRepository;

    /** Idempotent on sanction_ref_id: a retried creation request for an already-created sanction returns the existing account. */
    @Transactional
    public LoanAccount createAccount(CreateLoanAccountRequest request) {
        Optional<LoanAccount> existing = loanAccountRepository.findBySanctionRefId(request.sanctionRefId());
        if (existing.isPresent()) {
            return existing.get();
        }

        // Product terms are defaulted / validated here; the account keeps its own copy of them.
        AccountTermsResolver.AccountTerms terms = termsResolver.resolve(request);

        LoanAccount account = LoanAccount.builder()
                .customerId(request.customerId())
                .sanctionRefId(request.sanctionRefId())
                .productCode(terms.product().getProductCode())
                .productId(terms.product().getProductId())
                .accountType(terms.accountType())
                .scheduleType(terms.scheduleType())
                .sanctionedAmount(request.sanctionedAmount())
                .tenureMonths(request.tenureMonths())
                .rateType(terms.rateType())
                .interestRate(terms.interestRate())
                .benchmarkRateRef(terms.benchmarkRateRef())
                .spread(terms.spread())
                .dayCountConvention(terms.dayCountConvention())
                .repaymentFrequency(terms.repaymentFrequency())
                .moratoriumMonths(terms.moratoriumMonths())
                .balloonPercent(terms.balloonPercent())
                .stepUpPercent(terms.stepUpPercent())
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
        Map<Long, LoanProduct> products = new java.util.HashMap<>();
        productRepository.findAll().forEach(p -> products.put(p.getProductId(), p));
        int pageNo = 0;
        Page<LoanAccount> page;
        do {
            page = loanAccountRepository.findActiveFloatingByBenchmark(benchmarkRateRef, PageRequest.of(pageNo, 200));
            for (LoanAccount account : page.getContent()) {
                applyBenchmarkRate(account, newBenchmarkRate, products.get(account.getProductId()));
            }
            pageNo++;
        } while (page.hasNext());
    }

    /**
     * Resets a single account's rate from the benchmark rate in force on {@code businessDate}
     * (see {@link BenchmarkRateService}), regenerating its remaining schedule when the rate
     * actually changes. Idempotent: a repeat call finds the rate already applied and reports UNCHANGED.
     * Accounts that are not ACTIVE FLOATING (or reference a different benchmark than the caller asked
     * for) are SKIPPED rather than rejected.
     */
    @Transactional
    public RateResetResultResponse resetRateForAccount(Long accountId, LocalDate businessDate, String requiredBenchmarkRef) {
        businessDateService.assertNotFuture(businessDate, "businessDate");
        LoanAccount account = getById(accountId);
        String ref = account.getBenchmarkRateRef();
        if (account.getAccountStatus() != ACTIVE || account.getRateType() != LoanAccount.RateType.FLOATING || ref == null) {
            return rateResetSkipped(account, businessDate, "Account is not an ACTIVE floating-rate account");
        }
        if (requiredBenchmarkRef != null && !requiredBenchmarkRef.equals(ref)) {
            return rateResetSkipped(account, businessDate,
                    "Account references benchmark " + ref + ", not " + requiredBenchmarkRef);
        }
        BenchmarkRate benchmark = benchmarkRateService.getRateAsOf(ref, businessDate);
        BigDecimal previous = account.getInterestRate();
        LoanProduct product = productRepository.findById(account.getProductId()).orElse(null);
        boolean changed = applyBenchmarkRate(account, benchmark.getRate(), product);
        return new RateResetResultResponse(accountId, businessDate,
                changed ? RateResetResultResponse.Outcome.RESET : RateResetResultResponse.Outcome.UNCHANGED,
                ref, benchmark.getRate(), previous, account.getInterestRate(), null);
    }

    private RateResetResultResponse rateResetSkipped(LoanAccount account, LocalDate businessDate, String reason) {
        return new RateResetResultResponse(account.getAccountId(), businessDate,
                RateResetResultResponse.Outcome.SKIPPED, account.getBenchmarkRateRef(), null,
                account.getInterestRate(), account.getInterestRate(), reason);
    }

    /** @return true when the rate changed (and the schedule was regenerated), false when it was already current. */
    private boolean applyBenchmarkRate(LoanAccount account, BigDecimal newBenchmarkRate, LoanProduct product) {
        BigDecimal spread = account.getSpread() != null ? account.getSpread() : BigDecimal.ZERO;
        BigDecimal newRate = newBenchmarkRate.add(spread);
        // The product's rate band acts as a floor and a cap on the reset rate.
        if (product != null) {
            if (product.getMinInterestRate() != null && newRate.compareTo(product.getMinInterestRate()) < 0) {
                newRate = product.getMinInterestRate();
            }
            if (product.getMaxInterestRate() != null && newRate.compareTo(product.getMaxInterestRate()) > 0) {
                newRate = product.getMaxInterestRate();
            }
        }
        if (newRate.compareTo(account.getInterestRate()) == 0) {
            return false;
        }

        account.setInterestRate(newRate);
        loanAccountRepository.save(account);

        int version = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0);
        if (version == 0) {
            return true;
        }

        // An instalment that already has a demand is what the borrower owes now, so only the ones still to fall due are re-priced.
        java.util.Set<Integer> demanded = new java.util.HashSet<>(
                demandRepository.findDemandedInstallmentNumbers(account.getAccountId()));
        List<AmortizationScheduleEntry> pending = scheduleRepository
                .findByAccountIdAndScheduleVersionAndInstallmentStatus(account.getAccountId(), version, PENDING)
                .stream()
                .filter(e -> !demanded.contains(e.getInstallmentNumber()))
                .sorted(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .toList();
        if (pending.isEmpty()) {
            return true;
        }

        AmortizationScheduleEntry nextPending = pending.get(0);
        BigDecimal outstanding = nextPending.getOutstandingBalance().add(nextPending.getPrincipalComponent());
        scheduleService.regenerateSchedule(account, outstanding, newRate, pending.size(),
                nextPending.getDueDate(), nextPending.getInstallmentNumber());
        return true;
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
        businessDateService.assertNotFuture(asOfDate, "asOfDate");
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

    /**
     * Classifies a single account as of a date: days past due of its oldest unpaid instalment, the
     * resulting SMA/NPA status, and the OVERDUE flag on that instalment. Idempotent for a given
     * (account, date). Accounts outside the tracked statuses (closed, pending disbursement, ...) are
     * reported as skipped and left untouched.
     */
    @Transactional
    public DelinquencyResultResponse classifyOne(Long accountId, LocalDate asOfDate) {
        businessDateService.assertNotFuture(asOfDate, "asOfDate");
        LoanAccount account = getById(accountId);
        if (!DELINQUENCY_TRACKED_STATUSES.contains(account.getAccountStatus())) {
            return new DelinquencyResultResponse(accountId, asOfDate, 0, account.getAccountStatus(),
                    account.getAccountStatus(), false, true);
        }
        return classifyAccount(account, asOfDate);
    }

    private DelinquencyResultResponse classifyAccount(LoanAccount account, LocalDate asOfDate) {
        LoanAccount.AccountStatus previous = account.getAccountStatus();
        int version = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0);
        if (version == 0) {
            return new DelinquencyResultResponse(account.getAccountId(), asOfDate, 0, previous, previous, false, false);
        }

        List<AmortizationScheduleEntry> entries = scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(account.getAccountId(), version);

        Optional<AmortizationScheduleEntry> oldestUnpaid = entries.stream()
                .filter(e -> e.getInstallmentStatus() != AmortizationScheduleEntry.InstallmentStatus.PAID
                        && !e.getDueDate().isAfter(asOfDate))
                .min(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber));

        // A demand that is still open counts even when its schedule row is in an older schedule version (a rate reset
        // or part-payment writes a new version for the instalments not yet demanded only).
        Optional<LocalDate> oldestOpenDemandDue = demandRepository
                .findByAccountIdAndStatusNotOrderByInstallmentNumberAsc(account.getAccountId(), LoanDemand.DemandStatus.CLEARED)
                .stream().filter(d -> !d.getDueDate().isAfter(asOfDate) && d.outstanding().signum() > 0)
                .map(LoanDemand::getDueDate).min(Comparator.naturalOrder());
        Optional<LocalDate> oldestUnpaidDue = oldestUnpaid.map(AmortizationScheduleEntry::getDueDate);
        Optional<LocalDate> oldestDue = oldestUnpaidDue.isPresent() && oldestOpenDemandDue.isPresent()
                ? Optional.of(oldestUnpaidDue.get().isBefore(oldestOpenDemandDue.get()) ? oldestUnpaidDue.get() : oldestOpenDemandDue.get())
                : oldestUnpaidDue.isPresent() ? oldestUnpaidDue : oldestOpenDemandDue;

        if (oldestDue.isEmpty()) {
            if (account.getAccountStatus() != ACTIVE || account.getDaysPastDue() != 0) {
                account.setAccountStatus(ACTIVE);
                account.setDaysPastDue(0);
                loanAccountRepository.save(account);
            }
            return new DelinquencyResultResponse(account.getAccountId(), asOfDate, 0, previous,
                    account.getAccountStatus(), previous != account.getAccountStatus(), false);
        }

        long daysPastDue = ChronoUnit.DAYS.between(oldestDue.get(), asOfDate);
        LoanAccount.AccountStatus newStatus = classifyByDaysPastDue(daysPastDue);
        int storedDaysPastDue = (int) Math.max(0, daysPastDue);
        if (newStatus != account.getAccountStatus() || account.getDaysPastDue() != storedDaysPastDue) {
            account.setAccountStatus(newStatus);
            account.setDaysPastDue(storedDaysPastDue);
            loanAccountRepository.save(account);
        }

        if (oldestUnpaid.isPresent() && oldestUnpaid.get().getInstallmentStatus() == PENDING && daysPastDue > 0) {
            oldestUnpaid.get().setInstallmentStatus(AmortizationScheduleEntry.InstallmentStatus.OVERDUE);
            scheduleRepository.save(oldestUnpaid.get());
        }
        return new DelinquencyResultResponse(account.getAccountId(), asOfDate, daysPastDue, previous,
                account.getAccountStatus(), previous != account.getAccountStatus(), false);
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
