package com.bank.lending.maintenance.service;

import com.bank.lending.account.domain.LoanAccount.AccountType;
import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.account.domain.LoanAccount.RateType;
import com.bank.lending.product.dto.LoanProductRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * The base product set: the six standard products that migration V10 seeds (personal, business,
 * education, gold, auto and home loans). Creating them again after a clear gives the same starting
 * point as a fresh database. The sample schedule-type products added by V11 are not part of it.
 */
public final class BaseProducts {

    private BaseProducts() {
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static LoanProductRequest fixed(String code, String name, String description, String rate,
                                            String minRate, String maxRate, String minAmount, String maxAmount,
                                            int minTenure, int maxTenure) {
        return new LoanProductRequest(code, name, description, AccountType.UNSECURED, RateType.FIXED,
                null, DayCountConvention.ACT_365, null, d(rate), null,
                d(minRate), d(maxRate), null, null, d(minAmount), d(maxAmount), minTenure, maxTenure, null, null);
    }

    private static LoanProductRequest floating(String code, String name, String description, AccountType type,
                                               String rate, String spread, String minAmount, String maxAmount,
                                               int minTenure, int maxTenure) {
        return new LoanProductRequest(code, name, description, type, RateType.FLOATING,
                null, DayCountConvention.ACT_365, "REPO", d(rate), d(spread),
                d("8.0000"), d("13.0000"), d("1.0000"), d("5.0000"), d(minAmount), d(maxAmount),
                minTenure, maxTenure, null, null);
    }

    public static List<LoanProductRequest> all() {
        return List.of(
                fixed("PL01", "Personal Loan", "Unsecured fixed-rate personal loan",
                        "10.5000", "8.0000", "17.0000", "50000", "5000000", 6, 84),
                fixed("BL01", "Business Loan", "Unsecured fixed-rate business loan",
                        "11.5000", "9.0000", "17.0000", "100000", "10000000", 12, 120),
                fixed("EL01", "Education Loan", "Unsecured fixed-rate education loan",
                        "9.5000", "8.0000", "13.0000", "100000", "5000000", 12, 120),
                fixed("GL01", "Gold Loan", "Short-tenure fixed-rate loan against gold",
                        "9.0000", "7.0000", "13.0000", "10000", "1000000", 3, 36),
                floating("AL01", "Auto Loan", "Unsecured floating-rate vehicle loan linked to REPO",
                        AccountType.UNSECURED, "9.7500", "3.0000", "100000", "5000000", 12, 84),
                floating("HL01", "Home Loan", "Floating-rate home loan secured by property, linked to REPO",
                        AccountType.MORTGAGE, "8.7500", "2.5000", "500000", "50000000", 60, 360));
    }
}
