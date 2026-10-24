package com.bank.lending.maintenance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClearDataRequest(
        @Schema(description = "Must be exactly RESET. Guards against an accidental call.", example = "RESET")
        String confirm
) {
}
