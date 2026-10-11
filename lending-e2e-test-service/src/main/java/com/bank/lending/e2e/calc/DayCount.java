package com.bank.lending.e2e.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;

/**
 * Test-side day-count maths, written from the published convention definitions - NOT copied from
 * lending-service - so a defect in the product's implementation cannot hide behind an identical defect here.
 */
public final class DayCount {

    private DayCount() {
    }

    public enum Convention {
        ACT_365, ACT_ACT, ACT_360, THIRTY_360, THIRTY_E_360;

        /** Accepts API token (ACT_365) or display form (ACT/365, 30E/360). */
        public static Convention parse(String s) {
            String t = s.trim().toUpperCase().replace('/', '_');
            return switch (t) {
                case "30_360" -> THIRTY_360;
                case "30E_360" -> THIRTY_E_360;
                default -> valueOf(t);
            };
        }
    }

    /** Fraction of a year between start (inclusive) and end (exclusive). */
    public static BigDecimal yearFraction(LocalDate start, LocalDate end, Convention c) {
        MathContext mc = new MathContext(20, RoundingMode.HALF_EVEN);
        switch (c) {
            case ACT_365:
                return new BigDecimal(ChronoUnit.DAYS.between(start, end)).divide(BigDecimal.valueOf(365), mc);
            case ACT_360:
                return new BigDecimal(ChronoUnit.DAYS.between(start, end)).divide(BigDecimal.valueOf(360), mc);
            case ACT_ACT: {
                BigDecimal total = BigDecimal.ZERO;
                LocalDate cur = start;
                while (cur.isBefore(end)) {
                    LocalDate nextYear = LocalDate.of(cur.getYear() + 1, 1, 1);
                    LocalDate segEnd = nextYear.isBefore(end) ? nextYear : end;
                    int len = Year.isLeap(cur.getYear()) ? 366 : 365;
                    total = total.add(new BigDecimal(ChronoUnit.DAYS.between(cur, segEnd)).divide(BigDecimal.valueOf(len), mc));
                    cur = segEnd;
                }
                return total;
            }
            case THIRTY_360:
                return new BigDecimal(days30(start, end, false)).divide(BigDecimal.valueOf(360), mc);
            case THIRTY_E_360:
                return new BigDecimal(days30(start, end, true)).divide(BigDecimal.valueOf(360), mc);
            default:
                throw new IllegalArgumentException(c.name());
        }
    }

    /** 30/360: US (bond basis) rules when european=false, 30E/360 (Eurobond basis) when true. */
    static long days30(LocalDate s, LocalDate e, boolean european) {
        int d1 = s.getDayOfMonth();
        int d2 = e.getDayOfMonth();
        if (european) {
            d1 = Math.min(d1, 30);
            d2 = Math.min(d2, 30);
        } else {
            boolean sLastFeb = s.getMonthValue() == 2 && d1 == s.lengthOfMonth();
            boolean eLastFeb = e.getMonthValue() == 2 && d2 == e.lengthOfMonth();
            if (sLastFeb && eLastFeb) {
                d2 = 30;
            }
            if (sLastFeb) {
                d1 = 30;
            }
            if (d2 == 31 && d1 >= 30) {
                d2 = 30;
            }
            if (d1 == 31) {
                d1 = 30;
            }
        }
        return 360L * (e.getYear() - s.getYear()) + 30L * (e.getMonthValue() - s.getMonthValue()) + (d2 - d1);
    }

    /** Divisor for ONE day of interest on {@code date}. */
    public static int dailyBasis(Convention c, LocalDate date) {
        return switch (c) {
            case ACT_365 -> 365;
            case ACT_ACT -> Year.isLeap(date.getYear()) ? 366 : 365;
            case ACT_360, THIRTY_360, THIRTY_E_360 -> 360;
        };
    }

    /** One day of interest, rounded to paise. */
    public static BigDecimal dailyInterest(BigDecimal principal, BigDecimal annualRatePct, Convention c, LocalDate date) {
        return principal.multiply(annualRatePct).divide(BigDecimal.valueOf(100L * dailyBasis(c, date)), 2, RoundingMode.HALF_UP);
    }
}
