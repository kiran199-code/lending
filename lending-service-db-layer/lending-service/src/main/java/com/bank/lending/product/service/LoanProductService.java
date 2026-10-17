package com.bank.lending.product.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.schedule.util.ScheduleParameterRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Product master maintenance. Editing a product never rewrites existing accounts: its terms are
 * copied onto an account when it is created, and the product is consulted again only when that
 * individual account is rescheduled by a floating rate reset (rate band floor / cap). There is
 * deliberately no "reprice all accounts of this product" event.
 */
@Service
@RequiredArgsConstructor
public class LoanProductService {

    private static final BigDecimal RATE_LIMIT = new BigDecimal("100");

    private final LoanProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<LoanProduct> list(LoanProduct.Status status) {
        return status == null
                ? productRepository.findAllByOrderByProductCode()
                : productRepository.findByStatusOrderByProductCode(status);
    }

    @Transactional(readOnly = true)
    public LoanProduct getByCode(String productCode) {
        return productRepository.findByProductCode(productCode)
                .orElseThrow(() -> new ResourceNotFoundException("Loan product " + productCode + " not found"));
    }

    @Transactional
    public LoanProduct create(LoanProductRequest request) {
        if (productRepository.existsByProductCode(request.productCode())) {
            throw new InvalidAccountStateException("Loan product " + request.productCode() + " already exists");
        }
        validate(request);
        LoanProduct product = LoanProduct.builder().productCode(request.productCode()).version(0).build();
        apply(product, request);
        return productRepository.saveAndFlush(product);
    }

    @Transactional
    public LoanProduct update(String productCode, LoanProductRequest request) {
        LoanProduct product = getByCode(productCode);
        if (request.expectedVersion() != null && !request.expectedVersion().equals(product.getVersion())) {
            throw new InvalidAccountStateException("Loan product " + productCode
                    + " was changed by someone else (now version " + product.getVersion()
                    + "); reload it and apply your change again");
        }
        validate(request);
        apply(product, request);
        return productRepository.saveAndFlush(product);
    }

    private static void apply(LoanProduct p, LoanProductRequest r) {
        p.setProductName(r.productName().trim());
        p.setDescription(r.description());
        p.setAccountType(r.accountType());
        p.setRateType(r.rateType());
        p.setScheduleType(r.scheduleType() != null ? r.scheduleType() : LoanAccount.ScheduleType.EMI);
        p.setDayCountConvention(r.dayCountConvention() != null
                ? r.dayCountConvention() : LoanAccount.DayCountConvention.ACT_365);
        boolean floating = r.rateType() == LoanAccount.RateType.FLOATING;
        p.setBenchmarkRateRef(floating ? r.benchmarkRateRef().trim() : null);
        p.setDefaultInterestRate(r.defaultInterestRate());
        p.setDefaultSpread(floating ? r.defaultSpread() : null);
        p.setMinInterestRate(r.minInterestRate());
        p.setMaxInterestRate(r.maxInterestRate());
        p.setMinSpread(floating ? r.minSpread() : null);
        p.setMaxSpread(floating ? r.maxSpread() : null);
        p.setMinSanctionedAmount(r.minSanctionedAmount());
        p.setMaxSanctionedAmount(r.maxSanctionedAmount());
        p.setMinTenureMonths(r.minTenureMonths());
        p.setMaxTenureMonths(r.maxTenureMonths());
        LoanAccount.RepaymentFrequency frequency = frequencyOf(r);
        p.setRepaymentFrequency(frequency);
        p.setAllowedRepaymentFrequencies(LoanProduct.joinFrequencies(allowedOf(r)));
        p.setMoratoriumMonths(r.moratoriumMonths() != null ? r.moratoriumMonths() : 0);
        p.setMaxMoratoriumMonths(r.maxMoratoriumMonths());
        p.setBalloonPercent(r.balloonPercent() != null ? r.balloonPercent() : BigDecimal.ZERO);
        p.setMaxBalloonPercent(r.maxBalloonPercent());
        p.setStepUpPercent(r.stepUpPercent() != null ? r.stepUpPercent() : BigDecimal.ZERO);
        p.setMaxStepUpPercent(r.maxStepUpPercent());
        if (r.penalInterestEnabled() != null) {
            // The four penal fields travel together: a null rate or grace falls back to the default, a null cap means no cap.
            p.setPenalInterestEnabled(r.penalInterestEnabled());
            p.setPenalInterestRate(r.penalInterestRate() != null ? r.penalInterestRate() : new BigDecimal("24.0000"));
            p.setPenalGraceDays(r.penalGraceDays() != null ? r.penalGraceDays() : 0);
            p.setPenalCapPercent(r.penalCapPercent());
        }
        p.setStatus(r.status() != null ? r.status() : LoanProduct.Status.ACTIVE);
    }

    private static LoanAccount.RepaymentFrequency frequencyOf(LoanProductRequest r) {
        return r.repaymentFrequency() != null ? r.repaymentFrequency() : LoanAccount.RepaymentFrequency.MONTHLY;
    }

    private static List<LoanAccount.RepaymentFrequency> allowedOf(LoanProductRequest r) {
        return r.allowedRepaymentFrequencies() == null || r.allowedRepaymentFrequencies().isEmpty()
                ? List.of(frequencyOf(r))
                : r.allowedRepaymentFrequencies().stream().distinct().toList();
    }

    /** Throws IllegalArgumentException (HTTP 400) listing every problem found. */
    public static void validate(LoanProductRequest r) {
        List<String> problems = new ArrayList<>();
        boolean floating = r.rateType() == LoanAccount.RateType.FLOATING;

        if (floating && (r.benchmarkRateRef() == null || r.benchmarkRateRef().isBlank())) {
            problems.add("benchmarkRateRef is required for a FLOATING product");
        }
        if (!floating && (r.benchmarkRateRef() != null || r.defaultSpread() != null
                || r.minSpread() != null || r.maxSpread() != null)) {
            problems.add("benchmarkRateRef and the spread fields apply to FLOATING products only");
        }

        rateInRange("defaultInterestRate", r.defaultInterestRate(), problems);
        rateInRange("minInterestRate", r.minInterestRate(), problems);
        rateInRange("maxInterestRate", r.maxInterestRate(), problems);
        orderedBand("interest rate", r.minInterestRate(), r.maxInterestRate(), problems);
        orderedBand("spread", r.minSpread(), r.maxSpread(), problems);
        orderedBand("sanctioned amount", r.minSanctionedAmount(), r.maxSanctionedAmount(), problems);
        if (r.minSanctionedAmount() != null && r.minSanctionedAmount().signum() <= 0) {
            problems.add("minSanctionedAmount must be positive");
        }
        if (r.minTenureMonths() != null && r.minTenureMonths() <= 0) {
            problems.add("minTenureMonths must be positive");
        }
        if (r.minTenureMonths() != null && r.maxTenureMonths() != null
                && r.minTenureMonths() > r.maxTenureMonths()) {
            problems.add("minTenureMonths must not exceed maxTenureMonths");
        }

        if (r.defaultInterestRate() != null) {
            if (r.minInterestRate() != null && r.defaultInterestRate().compareTo(r.minInterestRate()) < 0
                    || r.maxInterestRate() != null && r.defaultInterestRate().compareTo(r.maxInterestRate()) > 0) {
                problems.add("defaultInterestRate must be inside the interest rate band");
            }
        }
        if (floating && r.defaultSpread() != null) {
            if (r.minSpread() != null && r.defaultSpread().compareTo(r.minSpread()) < 0
                    || r.maxSpread() != null && r.defaultSpread().compareTo(r.maxSpread()) > 0) {
                problems.add("defaultSpread must be inside the spread band");
            }
        }

        problems.addAll(ScheduleParameterRules.productProblems(
                r.scheduleType() != null ? r.scheduleType() : LoanAccount.ScheduleType.EMI,
                frequencyOf(r), allowedOf(r),
                r.moratoriumMonths(), r.maxMoratoriumMonths(),
                r.balloonPercent(), r.maxBalloonPercent(),
                r.stepUpPercent(), r.maxStepUpPercent()));

        rateInRange("penalInterestRate", r.penalInterestRate(), problems);
        if (r.penalInterestRate() != null && r.penalInterestRate().signum() >= 0
                && r.penalInterestRate().setScale(4, java.math.RoundingMode.HALF_UP).compareTo(RATE_LIMIT) >= 0) {
            problems.add("penalInterestRate must be below 99.99995 (four decimals are kept)");
        }
        if (r.penalGraceDays() != null && (r.penalGraceDays() < 0 || r.penalGraceDays() > 365)) {
            problems.add("penalGraceDays must be between 0 and 365");
        }
        if (r.penalCapPercent() != null && (r.penalCapPercent().signum() <= 0 || r.penalCapPercent().compareTo(new BigDecimal("1000")) > 0)) {
            problems.add("penalCapPercent must be above 0 and at most 1000, or empty for no cap");
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid loan product: " + String.join("; ", problems));
        }
    }

    private static void rateInRange(String field, BigDecimal value, List<String> problems) {
        if (value != null && (value.signum() < 0 || value.compareTo(RATE_LIMIT) >= 0)) {
            problems.add(field + " must be between 0 and 99.9999");
        }
    }

    private static void orderedBand(String name, BigDecimal min, BigDecimal max, List<String> problems) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            problems.add("minimum " + name + " must not exceed the maximum");
        }
    }
}
