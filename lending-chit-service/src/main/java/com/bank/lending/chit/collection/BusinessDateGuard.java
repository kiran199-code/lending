package com.bank.lending.chit.collection;

import com.bank.lending.chit.common.ChitException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Decides which day a batch call runs for. The platform business date (from lending-service) is the only day the
 * chit books may be written for; a caller that names a different day is refused rather than followed.
 */
public final class BusinessDateGuard {

    private BusinessDateGuard() {
    }

    /** The platform date, after checking the requested date (if any) is the same day. */
    public static LocalDate resolve(String requested, LocalDate platform) {
        if (requested != null && !requested.isBlank()) {
            LocalDate asked;
            try {
                asked = LocalDate.parse(requested.trim());
            } catch (DateTimeParseException e) {
                throw ChitException.badRequest("VALIDATION_FAILED", "businessDate must be a date like 2026-10-24");
            }
            if (!asked.equals(platform)) {
                throw ChitException.conflict("BUSINESS_DATE_MISMATCH",
                        "The batch is running for " + asked + " but the platform business date is " + platform + "; nothing was changed");
            }
        }
        return platform;
    }
}
