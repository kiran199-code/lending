package com.bank.lending.schedule.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.domain.InterestAccrual;
import com.bank.lending.schedule.dto.AccrualResultResponse;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.repository.InterestAccrualRepository;
import com.bank.lending.schedule.util.DayCountCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.OVERDUE;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PENDING;

/**
 * Daily interest accrual batch. Idempotent per (account, business_date) via
 * InterestAccrualRepository#insertIfAbsent, so re-running for the same date is safe.
 */
@Service
@RequiredArgsConstructor
public class InterestAccrualService {

    private static final int PAGE_SIZE = 200;

    private final LoanAccountRepository loanAccountRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final InterestAccrualRepository interestAccrualRepository;

    @Transactional
    public void runDailyAccrual(LocalDate businessDate) {
        int pageNo = 0;
        Page<LoanAccount> page;
        do {
            page = loanAccountRepository.findAllActiveForAccrual(PageRequest.of(pageNo, PAGE_SIZE));
            for (LoanAccount account : page.getContent()) {
                accrueForAccount(account, businessDate);
            }
            pageNo++;
        } while (page.hasNext());
    }

    /** Read accessor for the REST layer — the accrual posted for an account on a given business date, if any. */
    @Transactional(readOnly = true)
    public Optional<InterestAccrual> getAccrual(Long accountId, LocalDate businessDate) {
        return interestAccrualRepository.findByAccountIdAndBusinessDate(accountId, businessDate);
    }

    /**
     * Accrues interest for one account on one business date. Idempotent: if an accrual already
     * exists for (account, date) nothing is written and ALREADY_POSTED is returned. Accounts that
     * are not ACTIVE, or have nothing outstanding, are SKIPPED (not an error: the caller's list of
     * accounts can be a little stale by the time it gets here).
     */
    @Transactional
    public AccrualResultResponse accrueAccount(Long accountId, LocalDate businessDate) {
        LoanAccount account = loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
        if (account.getAccountStatus() != LoanAccount.AccountStatus.ACTIVE) {
            return new AccrualResultResponse(accountId, businessDate, AccrualResultResponse.Outcome.SKIPPED,
                    null, null, "Account status is " + account.getAccountStatus() + ", only ACTIVE accounts accrue");
        }
        return accrueForAccount(account, businessDate);
    }

    private AccrualResultResponse accrueForAccount(LoanAccount account, LocalDate businessDate) {
        Long accountId = account.getAccountId();
        BigDecimal outstanding = currentOutstandingPrincipal(accountId);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return new AccrualResultResponse(accountId, businessDate, AccrualResultResponse.Outcome.SKIPPED,
                    null, null, "No outstanding principal");
        }
        BigDecimal dailyInterest = DayCountCalculator.dailyInterest(
                outstanding, account.getInterestRate(), account.getDayCountConvention(), businessDate);
        int inserted = interestAccrualRepository.insertIfAbsent(accountId, businessDate, dailyInterest, outstanding);
        if (inserted == 1) {
            return new AccrualResultResponse(accountId, businessDate, AccrualResultResponse.Outcome.POSTED,
                    dailyInterest, outstanding, null);
        }
        return interestAccrualRepository.findByAccountIdAndBusinessDate(accountId, businessDate)
                .map(a -> new AccrualResultResponse(accountId, businessDate, AccrualResultResponse.Outcome.ALREADY_POSTED,
                        a.getAccruedAmount(), a.getOutstandingPrincipal(), null))
                .orElseGet(() -> new AccrualResultResponse(accountId, businessDate,
                        AccrualResultResponse.Outcome.ALREADY_POSTED, dailyInterest, outstanding, null));
    }

    /**
     * Outstanding principal as of "now" = the balance still owed before the next pending/overdue
     * installment's principal is paid, i.e. that installment's post-payment outstandingBalance
     * plus its own principalComponent. Returns zero once every installment is PAID.
     */
    private BigDecimal currentOutstandingPrincipal(Long accountId) {
        Optional<Integer> version = scheduleRepository.findCurrentVersion(accountId);
        if (version.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<AmortizationScheduleEntry> entries = scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version.get());
        return entries.stream()
                .filter(e -> e.getInstallmentStatus() == PENDING || e.getInstallmentStatus() == OVERDUE)
                .min(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .map(e -> e.getOutstandingBalance().add(e.getPrincipalComponent()))
                .orElse(BigDecimal.ZERO);
    }
}
