package com.bank.lending.e2e.calc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.calc.DayCount.Convention;
import com.bank.lending.e2e.calc.ScheduleInvariants.Row;
import com.bank.lending.e2e.calc.ScheduleInvariants.Terms;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleInvariantsTest {

    private static final BigDecimal TOL = new BigDecimal("0.05");

    /** 10,00,000 at 12% on 30/360 over 12 months: every period is exactly 1% of the opening balance. */
    private static List<Row> emiRows() {
        BigDecimal emi = new BigDecimal("88848.79");
        BigDecimal opening = new BigDecimal("1000000.00");
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            BigDecimal interest = opening.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = i == 12 ? opening : emi.subtract(interest);
            BigDecimal balance = opening.subtract(principal);
            rows.add(new Row(i, LocalDate.of(2026, 11, 1).plusMonths(i - 1L), principal, interest, balance));
            opening = balance;
        }
        return rows;
    }

    private static Terms terms() {
        return new Terms("EMI", "MONTHLY", 12, new BigDecimal("1000000"), new BigDecimal("12"), Convention.THIRTY_360, 0, null, null,
                LocalDate.of(2026, 11, 1));
    }

    @Test
    void aCorrectEmiScheduleHasNoViolations() {
        assertTrue(ScheduleInvariants.verify(terms(), emiRows(), TOL).isEmpty(), () -> String.join("; ", ScheduleInvariants.verify(terms(), emiRows(), TOL)));
    }

    @Test
    void wrongInterestIsCaught() {
        List<Row> rows = emiRows();
        Row r = rows.get(3);
        rows.set(3, new Row(r.no(), r.due(), r.principal().subtract(new BigDecimal("500")), r.interest().add(new BigDecimal("500")), r.balance().add(new BigDecimal("500"))));
        assertFalse(ScheduleInvariants.verify(terms(), rows, TOL).isEmpty());
    }

    @Test
    void wrongDueDateIsCaught() {
        List<Row> rows = emiRows();
        Row r = rows.get(5);
        rows.set(5, new Row(r.no(), r.due().plusDays(1), r.principal(), r.interest(), r.balance()));
        assertTrue(ScheduleInvariants.verify(terms(), rows, TOL).stream().anyMatch(v -> v.contains("due")));
    }

    @Test
    void principalNotAddingUpToDisbursedIsCaught() {
        List<Row> rows = emiRows();
        Row last = rows.get(11);
        rows.set(11, new Row(last.no(), last.due(), last.principal().subtract(new BigDecimal("100")), last.interest(), new BigDecimal("100")));
        assertFalse(ScheduleInvariants.verify(terms(), rows, TOL).isEmpty());
    }

    @Test
    void wrongInstalmentCountIsCaught() {
        List<Row> rows = emiRows();
        rows.remove(11);
        assertTrue(ScheduleInvariants.verify(terms(), rows, TOL).get(0).contains("count"));
    }

    @Test
    void interestOnlyRequiresBulletPrincipal() {
        BigDecimal p = new BigDecimal("600000");
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            BigDecimal interest = p.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
            rows.add(new Row(i, LocalDate.of(2026, 11, 1).plusMonths(i - 1L), i == 6 ? p : BigDecimal.ZERO, interest, i == 6 ? BigDecimal.ZERO : p));
        }
        Terms t = new Terms("INTEREST_ONLY", "MONTHLY", 6, p, new BigDecimal("12"), Convention.THIRTY_360, 0, null, null, LocalDate.of(2026, 11, 1));
        assertTrue(ScheduleInvariants.verify(t, rows, TOL).isEmpty());
        // a regular amortising schedule is NOT interest-only
        Terms wrongType = new Terms("INTEREST_ONLY", "MONTHLY", 12, new BigDecimal("1000000"), new BigDecimal("12"), Convention.THIRTY_360, 0, null, null,
                LocalDate.of(2026, 11, 1));
        assertFalse(ScheduleInvariants.verify(wrongType, emiRows(), TOL).isEmpty());
    }
}
