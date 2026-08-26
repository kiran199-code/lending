package com.bank.lending.account.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps LoanAccount.DayCountConvention enum constants (which cannot contain '/')
 * to the DB's stored string values ('ACT/365', 'ACT/ACT', '30/360'), which are
 * constrained by the CHECK clause in V1__create_loan_account.sql.
 */
@Converter(autoApply = false)
public class DayCountConventionConverter
        implements AttributeConverter<LoanAccount.DayCountConvention, String> {

    @Override
    public String convertToDatabaseColumn(LoanAccount.DayCountConvention attribute) {
        if (attribute == null) return null;
        return switch (attribute) {
            case ACT_365 -> "ACT/365";
            case ACT_ACT -> "ACT/ACT";
            case THIRTY_360 -> "30/360";
        };
    }

    @Override
    public LoanAccount.DayCountConvention convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return switch (dbData) {
            case "ACT/365" -> LoanAccount.DayCountConvention.ACT_365;
            case "ACT/ACT" -> LoanAccount.DayCountConvention.ACT_ACT;
            case "30/360" -> LoanAccount.DayCountConvention.THIRTY_360;
            default -> throw new IllegalArgumentException("Unknown day count convention: " + dbData);
        };
    }
}
