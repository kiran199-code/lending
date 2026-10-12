package com.bank.lending.businessdate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HolidayRequest(@NotBlank @Size(max = 100) String description) {
}
