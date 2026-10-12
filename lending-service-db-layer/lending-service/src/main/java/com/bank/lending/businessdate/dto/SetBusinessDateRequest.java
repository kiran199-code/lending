package com.bank.lending.businessdate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SetBusinessDateRequest(@NotNull LocalDate date, @Size(max = 200) String reason) {
}
