package com.bank.lending.mortgage.dto;

import com.bank.lending.mortgage.domain.Mortgage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record MortgageResponse(
        Long mortgageId,
        Long accountId,
        Mortgage.PropertyType propertyType,
        String propertyAddress,
        BigDecimal valuationAmount,
        LocalDate valuationDate,
        String valuerRef,
        BigDecimal ltvRatio,
        Mortgage.ChargeStatus chargeStatus,
        String cersaiRegistrationRef,
        String mortgageDeedRef,
        String insurancePolicyRef,
        Mortgage.LegalVerificationStatus legalVerificationStatus,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MortgageResponse from(Mortgage m) {
        return new MortgageResponse(
                m.getMortgageId(), m.getAccountId(), m.getPropertyType(), m.getPropertyAddress(),
                m.getValuationAmount(), m.getValuationDate(), m.getValuerRef(), m.getLtvRatio(),
                m.getChargeStatus(), m.getCersaiRegistrationRef(), m.getMortgageDeedRef(),
                m.getInsurancePolicyRef(), m.getLegalVerificationStatus(), m.getVersion(),
                m.getCreatedAt(), m.getUpdatedAt()
        );
    }
}
