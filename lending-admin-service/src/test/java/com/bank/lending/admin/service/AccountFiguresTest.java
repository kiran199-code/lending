package com.bank.lending.admin.service;

import com.bank.lending.admin.dto.AdminDtos.Figures;
import com.bank.lending.admin.dto.AdminDtos.PaymentRow;
import com.bank.lending.admin.dto.AdminDtos.ScheduleRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountFiguresTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 20);

    private static ScheduleRow row(int n, LocalDate due, String status, String balance) {
        BigDecimal principal = new BigDecimal("9000.00");
        BigDecimal interest = new BigDecimal("1000.00");
        int dpd = !"PAID".equals(status) && due.isBefore(TODAY) ? (int) java.time.temporal.ChronoUnit.DAYS.between(due, TODAY) : 0;
        return new ScheduleRow(n, due, principal, interest, principal.add(interest), new BigDecimal(balance), status, 1, dpd);
    }

    private static PaymentRow payment(String status, String amount) {
        return new PaymentRow(1L, TODAY, 1L, "PL01", 1, "EMI", new BigDecimal(amount), null, null, "NACH", status, null);
    }

    /** Three instalments: #1 paid, #2 overdue by 45 days, #3 upcoming. */
    private static List<ScheduleRow> schedule() {
        List<ScheduleRow> rows = new ArrayList<>();
        rows.add(row(1, LocalDate.of(2026, 10, 5), "PAID", "20000.00"));
        rows.add(row(2, TODAY.minusDays(45), "PENDING", "11000.00"));
        rows.add(row(3, TODAY.plusDays(10), "PENDING", "2000.00"));
        return rows;
    }

    @Test
    void derivesOverdueAndOutstandingFromTheFirstUnpaidInstalment() {
        Figures f = AccountFigures.compute("NPA_SMA1", new BigDecimal("30000.00"), schedule(),
                List.of(payment("SUCCESS", "10000.00"), payment("FAILED", "10000.00")), TODAY);

        assertThat(f.installmentsTotal()).isEqualTo(3);
        assertThat(f.installmentsPaid()).isEqualTo(1);
        assertThat(f.overdueInstallments()).isEqualTo(1);
        assertThat(f.overdueAmount()).isEqualByComparingTo("10000.00");
        assertThat(f.daysPastDue()).isEqualTo(45);
        assertThat(f.nextDueDate()).isEqualTo(TODAY.minusDays(45));
        // balance after instalment 2 (11000) plus its principal (9000) = owed before instalment 2
        assertThat(f.outstandingPrincipal()).isEqualByComparingTo("20000.00");
        assertThat(f.totalPaid()).isEqualByComparingTo("10000.00");
    }

    @Test
    void pendingDisbursementHasNoOutstandingAndClosedHasZero() {
        assertThat(AccountFigures.compute("PENDING_DISBURSEMENT", null, List.of(), List.of(), TODAY)
                .outstandingPrincipal()).isNull();
        assertThat(AccountFigures.compute("CLOSED", new BigDecimal("30000.00"), schedule(), List.of(), TODAY)
                .outstandingPrincipal()).isEqualByComparingTo("0");
    }

    @Test
    void totalsSplitPaidPendingAndOverdue() {
        var t = AccountFigures.totals(schedule());
        assertThat(t.paid()).isEqualTo(1);
        assertThat(t.overdue()).isEqualTo(1);
        assertThat(t.pending()).isEqualTo(1);
        assertThat(t.totalEmi()).isEqualByComparingTo("30000.00");
        assertThat(t.overdueEmi()).isEqualByComparingTo("10000.00");
    }
}
