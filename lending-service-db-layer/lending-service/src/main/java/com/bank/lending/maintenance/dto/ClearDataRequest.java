package com.bank.lending.maintenance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClearDataRequest(
        @Schema(description = "Must be exactly RESET. Guards against an accidental call.", example = "RESET")
        String confirm,
        @Schema(description = "Optional. Set it only when end of day failed and left the business date marked as running, and the batch "
                + "service has been checked and is idle. The clear then goes ahead and the date is reopened. Without it a clear is refused "
                + "while the business date is EOD_RUNNING.", example = "false")
        Boolean ignoreEndOfDay
) {
}
