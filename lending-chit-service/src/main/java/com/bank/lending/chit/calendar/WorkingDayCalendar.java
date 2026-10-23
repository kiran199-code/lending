package com.bank.lending.chit.calendar;

import java.time.LocalDate;

/** Answers whether a date is a working day. In the service this is lending-service's calendar; tests use a plain set. */
@FunctionalInterface
public interface WorkingDayCalendar {

    boolean isWorkingDay(LocalDate date);
}
