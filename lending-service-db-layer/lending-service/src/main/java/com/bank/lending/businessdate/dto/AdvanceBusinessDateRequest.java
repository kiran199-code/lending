package com.bank.lending.businessdate.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** The date the caller believes it is closing; makes a retried advance harmless. */
public record AdvanceBusinessDateRequest(@NotNull LocalDate expectedBusinessDate) {
}
