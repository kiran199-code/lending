package com.bank.lending.businessdate.dto;

import com.bank.lending.businessdate.domain.NonWorkingDay;

import java.time.LocalDate;

public record HolidayResponse(LocalDate date, String description) {
    public static HolidayResponse from(NonWorkingDay d) {
        return new HolidayResponse(d.getHolidayDate(), d.getDescription());
    }
}
