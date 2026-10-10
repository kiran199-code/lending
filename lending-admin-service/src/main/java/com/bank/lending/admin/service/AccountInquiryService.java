package com.bank.lending.admin.service;

import com.bank.lending.admin.common.NotFoundException;
import com.bank.lending.admin.dto.AdminDtos.AccountDetail;
import com.bank.lending.admin.dto.AdminDtos.AccrualResponse;
import com.bank.lending.admin.dto.AdminDtos.AccrualRow;
import com.bank.lending.admin.dto.AdminDtos.Figures;
import com.bank.lending.admin.dto.AdminDtos.MortgageDetail;
import com.bank.lending.admin.dto.AdminDtos.PaymentResponse;
import com.bank.lending.admin.dto.AdminDtos.PaymentRow;
import com.bank.lending.admin.dto.AdminDtos.ScheduleResponse;
import com.bank.lending.admin.dto.AdminDtos.ScheduleRow;
import com.bank.lending.admin.repository.AccountRepository;
import com.bank.lending.admin.repository.PaymentRepository;
import com.bank.lending.admin.repository.ScheduleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Everything shown on the single-account inquiry screens. */
@Service
public class AccountInquiryService {

    private final AccountRepository accounts;
    private final ScheduleRepository schedules;
    private final PaymentRepository payments;
    private final Clock clock;

    public AccountInquiryService(AccountRepository accounts, ScheduleRepository schedules,
                                 PaymentRepository payments, Clock clock) {
        this.accounts = accounts;
        this.schedules = schedules;
        this.payments = payments;
        this.clock = clock;
    }

    public long resolveId(String accountNumber) {
        return accounts.findIdByNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account with number " + accountNumber));
    }

    public AccountDetail detail(long accountId) {
        AccountDetail a = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("No account with id " + accountId));
        List<ScheduleRow> schedule = schedules.combined(accountId);
        List<PaymentRow> paymentRows = payments.forAccount(accountId);
        var accrual = payments.accrualTotals(accountId);
        Figures figures = AccountFigures
                .compute(a.accountStatus(), a.disbursedAmount(), schedule, paymentRows, LocalDate.now(clock))
                .withAccrual(accrual.total(), accrual.lastDate());
        return new AccountDetail(a.accountId(), a.accountNumber(), a.customerId(), a.sanctionRefId(), a.productCode(),
                a.accountType(), a.accountStatus(), a.sanctionedAmount(), a.disbursedAmount(), a.tenureMonths(),
                a.rateType(), a.interestRate(), a.benchmarkRateRef(), a.spread(), a.dayCountConvention(),
                a.emiAmount(), a.emiStartDate(), a.mortgageId(), a.version(), a.createdAt(), a.updatedAt(), figures);
    }

    /** version == null gives the combined view; otherwise exactly that schedule version. */
    public ScheduleResponse schedule(long accountId, Integer version) {
        requireAccount(accountId);
        List<ScheduleRow> rows = version == null ? schedules.combined(accountId) : schedules.byVersion(accountId, version);
        return new ScheduleResponse(accountId, version, schedules.versions(accountId), rows, AccountFigures.totals(rows));
    }

    public AccrualResponse accruals(long accountId, LocalDate from, LocalDate to) {
        requireAccount(accountId);
        List<AccrualRow> rows = payments.accrualsForAccount(accountId, from, to);
        BigDecimal total = rows.stream().map(AccrualRow::accruedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccrualResponse(rows, total, rows.size());
    }

    public PaymentResponse payments(long accountId) {
        requireAccount(accountId);
        List<PaymentRow> rows = payments.forAccount(accountId);
        BigDecimal total = BigDecimal.ZERO, principal = BigDecimal.ZERO, interest = BigDecimal.ZERO;
        int failed = 0;
        for (PaymentRow r : rows) {
            if ("SUCCESS".equals(r.status())) {
                total = total.add(r.amount());
                principal = principal.add(r.principalPaid() == null ? BigDecimal.ZERO : r.principalPaid());
                interest = interest.add(r.interestPaid() == null ? BigDecimal.ZERO : r.interestPaid());
            } else if ("FAILED".equals(r.status())) {
                failed++;
            }
        }
        return new PaymentResponse(rows, total, principal, interest, failed);
    }

    public MortgageDetail mortgage(long accountId) {
        requireAccount(accountId);
        return accounts.findMortgageByAccount(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " has no mortgage"));
    }

    private void requireAccount(long accountId) {
        if (accounts.findById(accountId).isEmpty()) {
            throw new NotFoundException("No account with id " + accountId);
        }
    }
}
