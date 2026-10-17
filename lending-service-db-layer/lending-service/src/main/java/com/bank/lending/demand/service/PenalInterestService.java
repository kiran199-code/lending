package com.bank.lending.demand.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.domain.PenalInterest;
import com.bank.lending.demand.dto.PenalAccrualResponse;
import com.bank.lending.demand.dto.PenalInterestRow;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.demand.repository.PaymentAllocationRepository;
import com.bank.lending.demand.repository.PenalInterestRepository;
import com.bank.lending.demand.util.PenalInterestCalculator;
import com.bank.lending.demand.util.PenalInterestCalculator.Accrual;
import com.bank.lending.demand.util.PenalInterestCalculator.Paid;
import com.bank.lending.demand.util.PenalInterestCalculator.Settings;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Charges penal interest on overdue demands, at the rate, grace days and cap of the account's product. The nightly
 * job calls {@link #accrueThrough} for each account with the business date; a payment calls it first with the day
 * before its value date, so what a borrower is asked to pay never depends on the job having run. Both are safe to
 * repeat: a day is charged once per demand, and a catch-up charges every missed day on the base it would have had.
 *
 * <p>Penal interest runs for ACTIVE and NPA accounts and stops once an account is closed, foreclosed or written off.
 * From SUBSTANDARD onward each charge is flagged {@code suspended} for accounting. Penal interest already charged is
 * not recomputed when a payment is back-dated or reversed (not built yet).
 */
@Service
@RequiredArgsConstructor
public class PenalInterestService {

    private static final Set<LoanAccount.AccountStatus> CHARGEABLE = EnumSet.of(
            LoanAccount.AccountStatus.ACTIVE, LoanAccount.AccountStatus.NPA_SMA0, LoanAccount.AccountStatus.NPA_SMA1,
            LoanAccount.AccountStatus.NPA_SMA2, LoanAccount.AccountStatus.NPA_SUBSTANDARD,
            LoanAccount.AccountStatus.NPA_DOUBTFUL, LoanAccount.AccountStatus.NPA_LOSS);

    private static final Set<LoanAccount.AccountStatus> SUSPENDED_FROM = EnumSet.of(
            LoanAccount.AccountStatus.NPA_SUBSTANDARD, LoanAccount.AccountStatus.NPA_DOUBTFUL,
            LoanAccount.AccountStatus.NPA_LOSS);

    private static final Set<PaymentAllocation.Bucket> BASE_BUCKETS = EnumSet.of(
            PaymentAllocation.Bucket.INTEREST, PaymentAllocation.Bucket.PRINCIPAL);

    private final LoanAccountRepository loanAccountRepository;
    private final LoanDemandRepository demandRepository;
    private final PenalInterestRepository penalRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final LoanProductRepository productRepository;
    private final BusinessDateService businessDateService;

    /** The penal terms of the account's product; switched off when the account has no product. */
    @Transactional(readOnly = true)
    public Settings settingsFor(LoanAccount account) {
        if (account.getProductId() == null) {
            return Settings.OFF;
        }
        return productRepository.findById(account.getProductId()).map(PenalInterestService::settingsOf).orElse(Settings.OFF);
    }

    static Settings settingsOf(LoanProduct p) {
        return new Settings(Boolean.TRUE.equals(p.getPenalInterestEnabled()), p.getPenalInterestRate(),
                p.getPenalGraceDays() == null ? 0 : p.getPenalGraceDays(), p.getPenalCapPercent());
    }

    /** Nightly job entry point for one account: charges every day up to and including the business date. */
    @Transactional
    public PenalAccrualResponse accrueForAccount(Long accountId, LocalDate businessDate) {
        businessDateService.assertNotFuture(businessDate, "businessDate");
        LoanAccount account = loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
        List<PenalInterest> written = accrueThrough(account, businessDate);
        BigDecimal total = written.stream().map(PenalInterest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = demandRepository.findByAccountIdAndStatusNotOrderByInstallmentNumberAsc(
                        accountId, LoanDemand.DemandStatus.CLEARED).stream()
                .map(LoanDemand::penalOutstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PenalAccrualResponse(accountId, businessDate, written.size(), total, outstanding);
    }

    /**
     * Charges every day up to and including {@code through} that has not been charged yet, on each demand of the account
     * that is not cleared (a demand whose principal and interest were paid by an advance still gets the days it was
     * overdue; the calculator charges nothing on a day when nothing was unpaid). Does nothing for an account that is not ACTIVE or NPA, or whose product
     * has penal interest switched off.
     *
     * @return the daily charges written
     */
    @Transactional
    public List<PenalInterest> accrueThrough(LoanAccount account, LocalDate through) {
        List<PenalInterest> written = new ArrayList<>();
        if (!CHARGEABLE.contains(account.getAccountStatus())) {
            return written;
        }
        Settings settings = settingsFor(account);
        if (!settings.enabled()) {
            return written;
        }
        boolean suspended = SUSPENDED_FROM.contains(account.getAccountStatus());
        List<LoanDemand> touched = new ArrayList<>();
        for (LoanDemand d : demandRepository.findByAccountIdAndStatusNotOrderByInstallmentNumberAsc(
                account.getAccountId(), LoanDemand.DemandStatus.CLEARED)) {
            List<Accrual> days = plan(account, d, settings, through);
            if (days.isEmpty()) {
                continue;
            }
            for (Accrual a : days) {
                written.add(PenalInterest.builder().accountId(account.getAccountId()).demandId(d.getDemandId())
                        .businessDate(a.date()).baseAmount(a.base()).annualRate(settings.annualRatePercent())
                        .amount(a.amount()).suspended(suspended).build());
            }
            d.setPenalAccrued(d.getPenalAccrued().add(PenalInterestCalculator.total(days)));
            touched.add(d);
        }
        if (!written.isEmpty()) {
            penalRepository.saveAll(written);
            demandRepository.saveAll(touched);
        }
        return written;
    }

    /**
     * Penal interest owed on a demand as of a date, including days not yet written (read only): what has been charged
     * plus what {@link #accrueThrough} would charge up to {@code through}, rounded to paise, less what has been paid.
     */
    @Transactional(readOnly = true)
    public BigDecimal penalOutstanding(LoanAccount account, LoanDemand demand, LocalDate through) {
        BigDecimal pending = BigDecimal.ZERO;
        if (CHARGEABLE.contains(account.getAccountStatus())) {
            Settings settings = settingsFor(account);
            if (settings.enabled()) {
                pending = PenalInterestCalculator.total(plan(account, demand, settings, through));
            }
        }
        return demand.getPenalAccrued().add(pending).setScale(2, java.math.RoundingMode.HALF_UP)
                .subtract(demand.getPenalPaid()).max(BigDecimal.ZERO);
    }

    /** The account's daily penal interest charges, oldest first. */
    @Transactional(readOnly = true)
    public List<PenalInterestRow> history(Long accountId) {
        return penalRepository.findByAccountIdOrderByBusinessDateAscDemandIdAsc(accountId).stream()
                .map(PenalInterestRow::from).toList();
    }

    private List<Accrual> plan(LoanAccount account, LoanDemand d, Settings settings, LocalDate through) {
        List<Paid> paid = allocationRepository.findByDemandIdAndBucketInOrderByValueDateAsc(d.getDemandId(), BASE_BUCKETS)
                .stream().map(a -> new Paid(a.getValueDate(), a.getAmount())).toList();
        return PenalInterestCalculator.accruals(d.getDueDate(), d.getPrincipalDue().add(d.getInterestDue()), paid,
                d.getPenalAccrued(), penalRepository.lastChargedDate(d.getDemandId()), through, settings,
                account.getDayCountConvention());
    }
}
