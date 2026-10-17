package com.bank.lending.refdata;

/** Request and response shapes of the currency and branch reference data. */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    /** {@code base} is true for the currency the books are kept in (accounting.base-currency). */
    public record CurrencyView(String currencyCode, String currencyName, int minorUnits, boolean active, boolean base) {
    }

    /** New currencies are created inactive unless {@code active} is true; an inactive currency cannot be given to a new account. */
    public record CurrencyRequest(String currencyCode, String currencyName, Integer minorUnits, Boolean active) {
    }

    public record BranchView(String branchCode, String branchName, boolean active, boolean defaultBranch) {
    }

    /** New branches are active unless {@code active} is false. */
    public record BranchRequest(String branchCode, String branchName, Boolean active) {
    }

    public record ActiveRequest(Boolean active) {
    }
}
