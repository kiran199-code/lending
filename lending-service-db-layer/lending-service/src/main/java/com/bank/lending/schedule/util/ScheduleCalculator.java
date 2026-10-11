package com.bank.lending.schedule.util;

import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.account.domain.LoanAccount.RepaymentFrequency;
import com.bank.lending.account.domain.LoanAccount.ScheduleType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the instalments of a loan for every {@link ScheduleType}. Pure calculation - no database,
 * no Spring - so it is easy to test; {@code AmortizationScheduleService} turns the result into rows.
 *
 * <p>How it works, in short:
 * <ul>
 *   <li>Each instalment period is priced as {@code outstanding x rate x yearFraction(start, due)}, so
 *       the day count convention decides the interest. Instalment 1 starts one period before its due
 *       date. Due dates are {@code firstDue + n x frequency months} (month-end safe).</li>
 *   <li>The first {@code moratoriumInstallments} instalments carry interest only (never for
 *       INTEREST_ONLY, which is interest only anyway).</li>
 *   <li>The amortising instalments are sized from the average rate per period under the day count;
 *       the real interest still differs a little from period to period (28 / 30 / 31 days), so the
 *       <b>last instalment always clears whatever balance is left</b>.</li>
 * </ul>
 */
public final class ScheduleCalculator {

    private static final MathContext MC = new MathContext(30);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScheduleCalculator() {
    }

    /**
     * @param installments        number of instalments (tenure months / frequency months)
     * @param moratoriumInstallments leading interest-only instalments
     * @param balloonAmount       BALLOON only: amount left for the last instalment (0 otherwise)
     * @param stepUpPercent       STEP_UP_EMI only: yearly rise of the instalment in percent
     * @param elapsedAmortizing   amortising instalments already paid before this run (a reschedule
     *                            continues the step-up year it is in); 0 for a new schedule
     */
    public record Request(ScheduleType type, DayCountConvention dayCount, RepaymentFrequency frequency,
                          BigDecimal principal, BigDecimal annualRatePercent, int installments,
                          LocalDate firstDueDate, int moratoriumInstallments, BigDecimal balloonAmount,
                          BigDecimal stepUpPercent, int elapsedAmortizing) {
    }

    /** {@code number} counts from 1 within this run. */
    public record Installment(int number, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
                              BigDecimal closingBalance) {
        public BigDecimal total() {
            return principal.add(interest);
        }
    }

    /**
     * @param regularInstallment the headline instalment: the first one that repays principal (the
     *                           first one of all for INTEREST_ONLY). For EQUAL_PRINCIPAL it is the
     *                           largest, for STEP_UP_EMI the lowest, for BALLOON it excludes the balloon.
     */
    public record Result(List<Installment> installments, BigDecimal regularInstallment) {
    }

    public static Result generate(Request r) {
        if (r.installments() <= 0) {
            throw new IllegalArgumentException("installments must be positive");
        }
        if (r.principal().signum() <= 0) {
            throw new IllegalArgumentException("principal must be positive");
        }
        int n = r.installments();
        int periodMonths = r.frequency().months();
        ScheduleType type = r.type();

        int moratorium = type == ScheduleType.INTEREST_ONLY ? 0 : Math.max(0, Math.min(r.moratoriumInstallments(), n - 1));
        int amortizing = n - moratorium;

        BigDecimal principal = r.principal();
        BigDecimal balloon = type == ScheduleType.BALLOON && r.balloonAmount() != null
                ? r.balloonAmount().min(principal).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal annualRate = r.annualRatePercent().divide(HUNDRED, MC);
        // Size the instalment on the rate the day count really charges per period (on average), so that
        // ACT/360 - which charges 365/360 of the nominal rate - does not leave a large final instalment.
        BigDecimal fractionSum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            fractionSum = fractionSum.add(DayCountCalculator.yearFraction(
                    r.firstDueDate().plusMonths((long) (i - 1) * periodMonths),
                    r.firstDueDate().plusMonths((long) i * periodMonths), r.dayCount()));
        }
        BigDecimal periodRate = annualRate.multiply(fractionSum, MC).divide(BigDecimal.valueOf(n), MC);

        BigDecimal level = BigDecimal.ZERO;          // EMI / BALLOON instalment
        BigDecimal principalPerInstallment = BigDecimal.ZERO;   // EQUAL_PRINCIPAL
        BigDecimal stepBase = BigDecimal.ZERO;       // STEP_UP_EMI first-year instalment
        BigDecimal stepFactor = BigDecimal.ONE;
        int perYear = Math.max(1, 12 / periodMonths);
        switch (type) {
            case EMI, BALLOON -> level = levelInstallment(principal, balloon, periodRate, amortizing);
            case EQUAL_PRINCIPAL -> principalPerInstallment = principal.divide(BigDecimal.valueOf(amortizing), 2, RoundingMode.HALF_UP);
            case STEP_UP_EMI -> {
                stepFactor = BigDecimal.ONE.add(nz(r.stepUpPercent()).divide(HUNDRED, MC));
                stepBase = stepUpBase(principal, periodRate, amortizing, stepFactor, perYear, r.elapsedAmortizing());
            }
            case INTEREST_ONLY -> { }
        }

        List<Installment> out = new ArrayList<>(n);
        BigDecimal outstanding = principal;
        for (int i = 0; i < n; i++) {
            LocalDate due = r.firstDueDate().plusMonths((long) i * periodMonths);
            LocalDate periodStart = r.firstDueDate().plusMonths((long) (i - 1) * periodMonths);
            BigDecimal interest = outstanding
                    .multiply(annualRate, MC)
                    .multiply(DayCountCalculator.yearFraction(periodStart, due, r.dayCount()), MC)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal principalPart;
            if (i == n - 1) {
                principalPart = outstanding;
            } else if (i < moratorium || type == ScheduleType.INTEREST_ONLY) {
                principalPart = BigDecimal.ZERO;
            } else {
                principalPart = switch (type) {
                    case EMI, BALLOON -> level.subtract(interest);
                    case EQUAL_PRINCIPAL -> principalPerInstallment;
                    case STEP_UP_EMI -> stepBase
                            .multiply(stepFactor.pow((r.elapsedAmortizing() + i - moratorium) / perYear, MC), MC)
                            .setScale(2, RoundingMode.HALF_UP)
                            .subtract(interest);
                    case INTEREST_ONLY -> BigDecimal.ZERO;
                };
                principalPart = principalPart.max(BigDecimal.ZERO).min(outstanding);
            }
            outstanding = outstanding.subtract(principalPart).setScale(2, RoundingMode.HALF_UP);
            out.add(new Installment(i + 1, due, principalPart, interest, outstanding));
        }

        BigDecimal regular = out.get(Math.min(moratorium, n - 1)).total();
        return new Result(out, regular);
    }

    /**
     * Level instalment that repays {@code principal} over {@code periods} while leaving
     * {@code balloon} to be paid at the end: (P - B/(1+r)^n) x r / (1 - (1+r)^-n).
     */
    static BigDecimal levelInstallment(BigDecimal principal, BigDecimal balloon, BigDecimal periodRate, int periods) {
        if (periodRate.signum() == 0) {
            return principal.subtract(balloon).divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
        }
        BigDecimal growth = BigDecimal.ONE.add(periodRate).pow(periods, MC);
        BigDecimal numerator = principal.subtract(balloon.divide(growth, MC)).multiply(periodRate, MC);
        BigDecimal denominator = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MC));
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /**
     * First-year instalment of a step-up schedule: the amount whose growing stream of instalments
     * has a present value equal to the principal.
     */
    static BigDecimal stepUpBase(BigDecimal principal, BigDecimal periodRate, int periods,
                                 BigDecimal stepFactor, int perYear, int elapsed) {
        BigDecimal discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(periodRate), MC);
        BigDecimal factor = BigDecimal.ONE;
        BigDecimal pvOfMultipliers = BigDecimal.ZERO;
        for (int i = 0; i < periods; i++) {
            factor = factor.multiply(discount, MC);
            pvOfMultipliers = pvOfMultipliers.add(factor.multiply(stepFactor.pow((elapsed + i) / perYear, MC), MC), MC);
        }
        return principal.divide(pvOfMultipliers, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
