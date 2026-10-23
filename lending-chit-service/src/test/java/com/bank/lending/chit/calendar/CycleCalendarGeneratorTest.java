package com.bank.lending.chit.calendar;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.TestRules;
import com.bank.lending.chit.domain.rules.ChitRules;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleCalendarGeneratorTest {

    /** Monday to Friday are working days, except the given holidays. */
    private static WorkingDayCalendar weekdays(LocalDate... holidays) {
        Set<LocalDate> h = Set.of(holidays);
        return d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !h.contains(d);
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void monthlyChitStartsInTheStartMonthWhenTheDueDayIsStillAhead() {
        ChitRules r = TestRules.baseline().startDate(d("2026-11-01")).build(); // due day 5, auction day 10
        List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, weekdays());
        assertEquals(20, c.size());
        assertEquals(new ScheduledCycle(1, d("2026-11-05"), d("2026-11-10")), c.get(0)); // Thu and Tue
        assertEquals(d("2026-12-07"), c.get(1).dueDate());   // 5 Dec 2026 is a Saturday, so Monday 7 Dec
        assertEquals(d("2026-12-10"), c.get(1).auctionDate());
        assertEquals(d("2028-06-05"), c.get(19).dueDate());   // cycle 20 = 19 months after Nov 2026, a Monday
    }

    @Test
    void aStartDateAfterTheDueDayMovesTheFirstCycleToNextMonth() {
        ChitRules r = TestRules.baseline().startDate(d("2026-11-06")).build();
        assertEquals(d("2026-12-07"), CycleCalendarGenerator.generate(r, weekdays()).get(0).dueDate());
        ChitRules same = TestRules.baseline().startDate(d("2026-11-05")).build(); // start on the due day itself counts
        assertEquals(d("2026-11-05"), CycleCalendarGenerator.generate(same, weekdays()).get(0).dueDate());
    }

    @Test
    void aHolidayPushesTheDateToTheNextWorkingDay() {
        ChitRules r = TestRules.baseline().startDate(d("2026-11-01")).build();
        List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, weekdays(d("2026-11-05"), d("2026-11-06")));
        assertEquals(d("2026-11-09"), c.get(0).dueDate()); // Thu and Fri are holidays, weekend follows
    }

    @Test
    void dayThirtyOneMeansTheLastDayOfShorterMonths() {
        ChitRules r = TestRules.baseline().startDate(d("2026-01-01")).instalmentDueDay(31).auctionDay(31).build();
        List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, d -> true); // every day working
        assertEquals(d("2026-01-31"), c.get(0).dueDate());
        assertEquals(d("2026-02-28"), c.get(1).dueDate());
        assertEquals(d("2026-03-31"), c.get(2).dueDate());
        assertEquals(d("2026-04-30"), c.get(3).dueDate());
        assertEquals(d("2027-02-28"), c.get(13).dueDate());
        ChitRules leap = TestRules.baseline().startDate(d("2027-12-01")).instalmentDueDay(31).auctionDay(31).build();
        assertEquals(d("2028-02-29"), CycleCalendarGenerator.generate(leap, d -> true).get(2).dueDate()); // leap year
    }

    @Test
    void weeklyChitUsesWeekdays() {
        // due Monday (1), auction Friday (5), starting Wednesday 4 Nov 2026: first Monday on or after is 9 Nov
        ChitRules r = TestRules.baseline().frequency(Frequency.WEEKLY).instalmentDueDay(1).auctionDay(5)
                .startDate(d("2026-11-04")).build();
        List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, weekdays());
        assertEquals(new ScheduledCycle(1, d("2026-11-09"), d("2026-11-13")), c.get(0));
        assertEquals(d("2026-11-16"), c.get(1).dueDate());
        assertEquals(d("2027-03-22"), c.get(19).dueDate());
    }

    @Test
    void fortnightlyChitSteps14Days() {
        ChitRules r = TestRules.baseline().frequency(Frequency.FORTNIGHTLY).instalmentDueDay(2).auctionDay(4)
                .startDate(d("2026-11-02")).build(); // Monday 2 Nov is a Monday, so due day 2 (Tuesday) = 3 Nov
        List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, weekdays());
        assertEquals(d("2026-11-03"), c.get(0).dueDate());
        assertEquals(d("2026-11-17"), c.get(1).dueDate());
        assertEquals(d("2026-11-05"), c.get(0).auctionDate());
    }

    @Test
    void aWeeklyAuctionThatRollsIntoNextWeeksDueDateIsRefused() {
        // due Monday, auction Sunday: the Sunday auction rolls to Monday, the same day the next instalment falls due
        ChitRules r = TestRules.baseline().frequency(Frequency.WEEKLY).instalmentDueDay(1).auctionDay(7)
                .startDate(d("2026-11-02")).build();
        assertEquals("CALENDAR_OVERLAP", assertThrows(ChitException.class,
                () -> CycleCalendarGenerator.generate(r, weekdays())).code());
    }

    @Test
    void noWorkingDayAtAllIsRefused() {
        ChitRules r = TestRules.valid();
        assertEquals("CALENDAR_UNAVAILABLE", assertThrows(ChitException.class,
                () -> CycleCalendarGenerator.generate(r, date -> false)).code());
    }

    @Test
    void datesAlwaysAdvanceAndTheAuctionNeverPrecedesTheDueDate() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 300; trial++) {
            Set<LocalDate> holidays = new HashSet<>();
            for (int k = 0; k < 40; k++) {
                holidays.add(d("2026-01-01").plusDays(rnd.nextInt(900)));
            }
            int due = 1 + rnd.nextInt(20);
            int auction = due + rnd.nextInt(8);
            ChitRules r = TestRules.baseline().instalmentDueDay(due).auctionDay(Math.min(auction, 31))
                    .startDate(d("2026-01-01").plusDays(rnd.nextInt(200))).build();
            List<ScheduledCycle> c = CycleCalendarGenerator.generate(r, weekdays(holidays.toArray(new LocalDate[0])));
            for (int i = 0; i < c.size(); i++) {
                assertFalse(c.get(i).auctionDate().isBefore(c.get(i).dueDate()), "trial " + trial + " cycle " + (i + 1));
                assertTrue(weekdays(holidays.toArray(new LocalDate[0])).isWorkingDay(c.get(i).dueDate()));
                assertTrue(!c.get(i).dueDate().isBefore(r.startDate()), "due before start");
                if (i > 0) {
                    assertTrue(c.get(i).dueDate().isAfter(c.get(i - 1).dueDate()), "due dates must advance");
                }
            }
        }
    }
}
