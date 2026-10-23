package com.bank.lending.chit.calendar;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.rules.ChitRules;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the calendar rules into the whole list of cycles.
 *
 * <ul>
 *   <li>MONTHLY: the due day and auction day are days of the month (1 to 31). A day past the end of a short month
 *       means the last day of that month. The first cycle falls in the first month whose due date is on or after the
 *       start date.</li>
 *   <li>WEEKLY and FORTNIGHTLY: the due day and auction day are weekdays (1 = Monday ... 7 = Sunday). The first due
 *       date is the first such weekday on or after the start date; later cycles follow 7 or 14 days apart. The auction
 *       is {@code auctionDay - instalmentDueDay} days after the due date.</li>
 *   <li>A date that is not a working day moves <b>forward</b> to the next working day (at most {@value #MAX_ROLL} days).
 *       Rolling forward is monotone, so an auction is never before its due date.</li>
 * </ul>
 *
 * <p>Refused with CALENDAR_OVERLAP when a cycle's auction would not be strictly before the next cycle's due date
 * (for example a weekly chit whose Sunday auction rolls onto Monday's next due date), and with CALENDAR_UNAVAILABLE when
 * no working day is found nearby. The rules must already be valid ({@code RulesValidator}).
 */
public final class CycleCalendarGenerator {

    public static final int MAX_ROLL = 14;

    private CycleCalendarGenerator() {
    }

    public static List<ScheduledCycle> generate(ChitRules rules, WorkingDayCalendar calendar) {
        int n = rules.cycleCount();
        List<ScheduledCycle> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate[] raw = rawDates(rules, i);
            LocalDate due = roll(raw[0], calendar);
            LocalDate auction = roll(raw[1], calendar);
            out.add(new ScheduledCycle(i + 1, due, auction));
        }
        for (int i = 0; i + 1 < out.size(); i++) {
            ScheduledCycle a = out.get(i);
            ScheduledCycle b = out.get(i + 1);
            if (!a.auctionDate().isBefore(b.dueDate())) {
                throw new ChitException("CALENDAR_OVERLAP", "The auction of cycle " + a.cycleNo() + " (" + a.auctionDate()
                        + ") is not before the next instalment is due (" + b.dueDate()
                        + "). Choose an earlier auction day or a different frequency.");
            }
        }
        return out;
    }

    /** {due, auction} before any working-day adjustment, for the cycle at zero-based position {@code i}. */
    static LocalDate[] rawDates(ChitRules r, int i) {
        LocalDate start = r.startDate();
        if (r.frequency() == Frequency.MONTHLY) {
            YearMonth first = YearMonth.from(start);
            if (monthDate(first, r.instalmentDueDay()).isBefore(start)) {
                first = first.plusMonths(1);
            }
            YearMonth m = first.plusMonths(i);
            return new LocalDate[]{monthDate(m, r.instalmentDueDay()), monthDate(m, r.auctionDay())};
        }
        int step = r.frequency() == Frequency.WEEKLY ? 7 : 14;
        int shift = (r.instalmentDueDay() - start.getDayOfWeek().getValue() + 7) % 7;
        LocalDate due = start.plusDays(shift).plusDays((long) step * i);
        return new LocalDate[]{due, due.plusDays(r.auctionDay() - r.instalmentDueDay())};
    }

    private static LocalDate monthDate(YearMonth m, int day) {
        return m.atDay(Math.min(day, m.lengthOfMonth()));
    }

    private static LocalDate roll(LocalDate date, WorkingDayCalendar calendar) {
        LocalDate d = date;
        for (int i = 0; i <= MAX_ROLL; i++) {
            if (calendar.isWorkingDay(d)) {
                return d;
            }
            d = d.plusDays(1);
        }
        throw new ChitException("CALENDAR_UNAVAILABLE", "No working day found within " + MAX_ROLL + " days after " + date);
    }
}
