package com.bank.lending.chit.platform;

import java.time.LocalDate;

/**
 * The platform's business date, owned by lending-service. Every date the chit service stamps on a posting or checks a
 * rule against comes from here, never from the wall clock, so the chit books agree with the loan books.
 */
public interface BusinessDateSource {

    /** The current business date. Throws BUSINESS_DATE_UNAVAILABLE (HTTP 503) when it cannot be read; nothing is guessed. */
    LocalDate today();
}
