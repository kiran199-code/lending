package com.bank.lending.chit.calendar;

import java.time.LocalDate;

/** One planned cycle: its number, the date the instalment is due and the date of the auction. Both are working days. */
public record ScheduledCycle(int cycleNo, LocalDate dueDate, LocalDate auctionDate) {
}
