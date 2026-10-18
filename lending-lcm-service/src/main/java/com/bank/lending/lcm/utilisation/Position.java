package com.bank.lending.lcm.utilisation;

import java.math.BigDecimal;

/** What one drawdown (a loan account) currently owes against its facility. */
public record Position(
        long id,
        long facilityId,
        String referenceType,
        String referenceId,
        BigDecimal drawn,
        BigDecimal repaid,
        BigDecimal writtenOff,
        String status) {

    public BigDecimal outstanding() {
        return drawn.subtract(repaid).subtract(writtenOff);
    }
}
