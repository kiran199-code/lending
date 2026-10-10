package com.bank.lending.admin.service;

import com.bank.lending.admin.dto.AdminDtos.Figures;
import com.bank.lending.admin.dto.AdminDtos.PaymentRow;
import com.bank.lending.admin.dto.AdminDtos.ScheduleRow;
import com.bank.lending.admin.dto.AdminDtos.ScheduleTotals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure calculations over an account's schedule and payments; no I/O, so easy to test. */
public final class AccountFigures {

    private AccountFigures() {
    }

    public static Figures compute(String accountStatus, BigDecimal disbursedAmount, List<ScheduleRow> schedule,
                                  List<PaymentRow> payments, LocalDate today) {
        int paid = (int) schedule.stream().filter(r -> "PAID".equals(r.status())).count();

        Optional<ScheduleRow> next = schedule.stream()
                .filter(r -> !"PAID".equals(r.status()))
                .min(Comparator.comparing(ScheduleRow::installmentNumber));

        List<ScheduleRow> overdue = schedule.stream()
                .filter(r -> !"PAID".equals(r.status()) && r.dueDate().isBefore(today))
                .toList();
        BigDecimal overdueAmount = overdue.stream().map(ScheduleRow::emi).reduce(BigDecimal.ZERO, BigDecimal::add);
        int dpd = overdue.stream().mapToInt(ScheduleRow::daysPastDue).max().orElse(0);

        BigDecimal totalPaid = payments.stream()
                .filter(p -> "SUCCESS".equals(p.status()))
                .map(PaymentRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Figures(schedule.size(), paid, next.map(ScheduleRow::dueDate).orElse(null),
                next.map(ScheduleRow::emi).orElse(null), overdue.size(), overdueAmount, dpd,
                outstandingPrincipal(accountStatus, disbursedAmount, schedule, next), totalPaid, null, null);
    }

    /** Null while the loan is not disbursed; zero once closed; otherwise the balance before the next unpaid instalment. */
    static BigDecimal outstandingPrincipal(String status, BigDecimal disbursed, List<ScheduleRow> schedule,
                                           Optional<ScheduleRow> nextUnpaid) {
        if ("PENDING_DISBURSEMENT".equals(status)) {
            return null;
        }
        if ("CLOSED".equals(status) || "FORECLOSED".equals(status)) {
            return BigDecimal.ZERO;
        }
        if (nextUnpaid.isPresent()) {
            return nextUnpaid.get().outstandingBalance().add(nextUnpaid.get().principal());
        }
        return schedule.isEmpty() ? disbursed : BigDecimal.ZERO;
    }

    public static ScheduleTotals totals(List<ScheduleRow> rows) {
        BigDecimal principal = BigDecimal.ZERO, interest = BigDecimal.ZERO, emi = BigDecimal.ZERO;
        BigDecimal paidEmi = BigDecimal.ZERO, pendingEmi = BigDecimal.ZERO, overdueEmi = BigDecimal.ZERO;
        int paid = 0, pending = 0, overdue = 0;
        for (ScheduleRow r : rows) {
            principal = principal.add(r.principal());
            interest = interest.add(r.interest());
            emi = emi.add(r.emi());
            if ("PAID".equals(r.status())) {
                paid++;
                paidEmi = paidEmi.add(r.emi());
            } else if (r.daysPastDue() > 0) {
                overdue++;
                overdueEmi = overdueEmi.add(r.emi());
            } else {
                pending++;
                pendingEmi = pendingEmi.add(r.emi());
            }
        }
        return new ScheduleTotals(principal, interest, emi, paidEmi, pendingEmi, overdueEmi, paid, pending, overdue);
    }
}
