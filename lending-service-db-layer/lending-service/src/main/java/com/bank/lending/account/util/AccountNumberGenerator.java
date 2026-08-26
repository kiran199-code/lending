package com.bank.lending.account.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates candidate account numbers (product code + date + random suffix,
 * <= 20 chars to fit the account_number column). Callers are expected to
 * retry on a unique-constraint violation (see LoanAccountService) rather than
 * relying on this generator alone for uniqueness.
 */
@Component
public class AccountNumberGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");

    public String next(String productCode) {
        String prefix = productCode == null ? "LN" : productCode.replaceAll("[^A-Za-z0-9]", "");
        if (prefix.isEmpty()) {
            prefix = "LN";
        }
        if (prefix.length() > 4) {
            prefix = prefix.substring(0, 4);
        }
        String datePart = LocalDate.now().format(DATE_FORMAT);
        String randomPart = String.format("%06d", RANDOM.nextInt(1_000_000));
        String candidate = prefix + datePart + randomPart;
        return candidate.length() > 20 ? candidate.substring(0, 20) : candidate;
    }
}
