package com.bank.lending.product.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.schedule.util.ScheduleParameterRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns "product + whatever the caller supplied" into the final account terms.
 *
 * <p>Anything the caller leaves out is defaulted from the product. Anything the caller supplies is
 * an override and must stay inside the product's bands; account type and rate type cannot differ
 * from the product at all. All problems are reported together (IllegalArgumentException, HTTP 400).
 */
@Component
@RequiredArgsConstructor
public class AccountTermsResolver {

    private final LoanProductRepository productRepository;

    public record AccountTerms(
            LoanProduct product,
            LoanAccount.AccountType accountType,
            LoanAccount.RateType rateType,
            LoanAccount.ScheduleType scheduleType,
            LoanAccount.DayCountConvention dayCountConvention,
            BigDecimal interestRate,
            String benchmarkRateRef,
            BigDecimal spread,
            LoanAccount.RepaymentFrequency repaymentFrequency,
            int moratoriumMonths,
            BigDecimal balloonPercent,
            BigDecimal stepUpPercent) {
    }

    public AccountTerms resolve(CreateLoanAccountRequest request) {
        LoanProduct product = productRepository.findByProductCode(request.productCode())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product " + request.productCode() + " not found"));
        if (product.getStatus() != LoanProduct.Status.ACTIVE) {
            throw new InvalidAccountStateException("Loan product " + product.getProductCode()
                    + " is inactive and cannot be used for new accounts");
        }

        List<String> problems = new ArrayList<>();
        String code = product.getProductCode();

        LoanAccount.AccountType accountType = request.accountType() != null ? request.accountType() : product.getAccountType();
        if (accountType != product.getAccountType()) {
            problems.add("accountType " + accountType + " does not match product " + code + " (" + product.getAccountType() + ")");
        }
        LoanAccount.RateType rateType = request.rateType() != null ? request.rateType() : product.getRateType();
        if (rateType != product.getRateType()) {
            problems.add("rateType " + rateType + " does not match product " + code + " (" + product.getRateType() + ")");
        }

        BigDecimal rate = request.interestRate() != null ? request.interestRate() : product.getDefaultInterestRate();
        checkBand("interestRate", rate, product.getMinInterestRate(), product.getMaxInterestRate(), code, problems);

        String benchmark;
        BigDecimal spread;
        if (product.getRateType() == LoanAccount.RateType.FLOATING) {
            benchmark = request.benchmarkRateRef() != null && !request.benchmarkRateRef().isBlank()
                    ? request.benchmarkRateRef().trim() : product.getBenchmarkRateRef();
            spread = request.spread() != null ? request.spread() : product.getDefaultSpread();
            if (spread != null) {
                checkBand("spread", spread, product.getMinSpread(), product.getMaxSpread(), code, problems);
            }
        } else {
            benchmark = null;
            spread = null;
            if (request.benchmarkRateRef() != null || request.spread() != null) {
                problems.add("benchmarkRateRef and spread apply to FLOATING products only; product " + code + " is FIXED");
            }
        }

        checkBand("sanctionedAmount", request.sanctionedAmount(),
                product.getMinSanctionedAmount(), product.getMaxSanctionedAmount(), code, problems);
        checkBand("tenureMonths", request.tenureMonths() == null ? null : BigDecimal.valueOf(request.tenureMonths()),
                product.getMinTenureMonths() == null ? null : BigDecimal.valueOf(product.getMinTenureMonths()),
                product.getMaxTenureMonths() == null ? null : BigDecimal.valueOf(product.getMaxTenureMonths()),
                code, problems);

        // Schedule parameters: default from the product, overrides must stay inside what it allows.
        LoanAccount.RepaymentFrequency frequency = request.repaymentFrequency() != null
                ? request.repaymentFrequency() : product.getRepaymentFrequency();
        if (!product.allowedFrequencies().contains(frequency)) {
            problems.add("repaymentFrequency " + frequency + " is not allowed; product " + code
                    + " allows " + product.allowedFrequencies());
        }
        int moratorium = request.moratoriumMonths() != null ? request.moratoriumMonths() : product.getMoratoriumMonths();
        if (product.getMaxMoratoriumMonths() != null && moratorium > product.getMaxMoratoriumMonths()) {
            problems.add("moratoriumMonths " + moratorium + " is above the maximum " + product.getMaxMoratoriumMonths());
        }
        BigDecimal balloon = request.balloonPercent() != null ? request.balloonPercent() : product.getBalloonPercent();
        checkBand("balloonPercent", balloon, null, product.getMaxBalloonPercent(), code, problems);
        BigDecimal stepUp = request.stepUpPercent() != null ? request.stepUpPercent() : product.getStepUpPercent();
        checkBand("stepUpPercent", stepUp, null, product.getMaxStepUpPercent(), code, problems);
        problems.addAll(ScheduleParameterRules.parameterProblems(product.getScheduleType(), frequency,
                request.tenureMonths(), moratorium, balloon, stepUp));

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Account does not satisfy product " + code + ": "
                    + String.join("; ", problems));
        }

        LoanAccount.DayCountConvention dayCount = request.dayCountConvention() != null
                ? request.dayCountConvention() : product.getDayCountConvention();
        return new AccountTerms(product, accountType, rateType, product.getScheduleType(), dayCount,
                rate, benchmark, spread, frequency, moratorium, balloon, stepUp);
    }

    private static void checkBand(String field, BigDecimal value, BigDecimal min, BigDecimal max,
                                  String code, List<String> problems) {
        if (value == null) {
            return;
        }
        if (min != null && value.compareTo(min) < 0) {
            problems.add(field + " " + value.stripTrailingZeros().toPlainString() + " is below the minimum "
                    + min.stripTrailingZeros().toPlainString());
        }
        if (max != null && value.compareTo(max) > 0) {
            problems.add(field + " " + value.stripTrailingZeros().toPlainString() + " is above the maximum "
                    + max.stripTrailingZeros().toPlainString());
        }
    }
}
