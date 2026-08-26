package com.bank.lending.mortgage.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.mortgage.domain.Mortgage;
import com.bank.lending.mortgage.domain.MortgageValuationHistory;
import com.bank.lending.mortgage.dto.CreateMortgageRequest;
import com.bank.lending.mortgage.repository.MortgageRepository;
import com.bank.lending.mortgage.repository.MortgageValuationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MortgageService {

    private final MortgageRepository mortgageRepository;
    private final MortgageValuationHistoryRepository valuationHistoryRepository;
    private final LoanAccountRepository loanAccountRepository;

    /** Idempotent on account_id (Mortgage.accountId is unique). */
    @Transactional
    public Mortgage createMortgage(CreateMortgageRequest request) {
        Optional<Mortgage> existing = mortgageRepository.findByAccountId(request.accountId());
        if (existing.isPresent()) {
            return existing.get();
        }

        LoanAccount account = loanAccountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + request.accountId() + " not found"));
        if (account.getAccountType() != LoanAccount.AccountType.MORTGAGE) {
            throw new InvalidAccountStateException("Account " + request.accountId() + " is not a MORTGAGE account");
        }

        BigDecimal ltv = ltvRatio(account.getSanctionedAmount(), request.valuationAmount());

        Mortgage mortgage = Mortgage.builder()
                .accountId(request.accountId())
                .propertyType(request.propertyType())
                .propertyAddress(request.propertyAddress())
                .valuationAmount(request.valuationAmount())
                .valuationDate(request.valuationDate())
                .valuerRef(request.valuerRef())
                .ltvRatio(ltv)
                .chargeStatus(Mortgage.ChargeStatus.PENDING)
                .legalVerificationStatus(Mortgage.LegalVerificationStatus.PENDING)
                .version(0)
                .build();
        Mortgage saved = mortgageRepository.save(mortgage);

        valuationHistoryRepository.save(MortgageValuationHistory.builder()
                .mortgageId(saved.getMortgageId())
                .valuationAmount(request.valuationAmount())
                .valuationDate(request.valuationDate())
                .valuerRef(request.valuerRef())
                .valuationType(MortgageValuationHistory.ValuationType.INITIAL)
                .build());

        return saved;
    }

    /**
     * Registers the legal charge and links the mortgage back onto the loan account —
     * required before LoanAccountService#disburse will allow disbursement of a MORTGAGE
     * account (see chk_unsecured_no_mortgage / V3 migration).
     */
    @Transactional
    public Mortgage registerCharge(Long mortgageId, String cersaiRegistrationRef, String mortgageDeedRef) {
        Mortgage mortgage = getOrThrow(mortgageId);
        mortgage.setCersaiRegistrationRef(cersaiRegistrationRef);
        mortgage.setMortgageDeedRef(mortgageDeedRef);
        mortgage.setChargeStatus(Mortgage.ChargeStatus.REGISTERED);
        Mortgage saved = mortgageRepository.save(mortgage);

        LoanAccount account = loanAccountRepository.findById(mortgage.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + mortgage.getAccountId() + " not found"));
        account.setMortgageId(mortgageId);
        loanAccountRepository.save(account);

        return saved;
    }

    @Transactional
    public MortgageValuationHistory recordValuation(Long mortgageId, BigDecimal valuationAmount, LocalDate valuationDate,
                                                      String valuerRef, MortgageValuationHistory.ValuationType type) {
        Mortgage mortgage = getOrThrow(mortgageId);
        LoanAccount account = loanAccountRepository.findById(mortgage.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + mortgage.getAccountId() + " not found"));

        mortgage.setValuationAmount(valuationAmount);
        mortgage.setValuationDate(valuationDate);
        mortgage.setValuerRef(valuerRef);
        mortgage.setLtvRatio(ltvRatio(account.getSanctionedAmount(), valuationAmount));
        mortgageRepository.save(mortgage);

        return valuationHistoryRepository.save(MortgageValuationHistory.builder()
                .mortgageId(mortgageId)
                .valuationAmount(valuationAmount)
                .valuationDate(valuationDate)
                .valuerRef(valuerRef)
                .valuationType(type)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Mortgage> findDueForRevaluation(LocalDate cutoffDate) {
        return mortgageRepository.findDueForRevaluation(cutoffDate);
    }

    /** Read accessor for the REST layer — same lookup used internally, exposed publicly. */
    @Transactional(readOnly = true)
    public Mortgage getById(Long mortgageId) {
        return getOrThrow(mortgageId);
    }

    @Transactional(readOnly = true)
    public Mortgage getByAccountId(Long accountId) {
        return mortgageRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Mortgage for account " + accountId + " not found"));
    }

    @Transactional(readOnly = true)
    public List<MortgageValuationHistory> getValuationHistory(Long mortgageId) {
        return valuationHistoryRepository.findByMortgageIdOrderByValuationDateDesc(mortgageId);
    }

    private Mortgage getOrThrow(Long mortgageId) {
        return mortgageRepository.findById(mortgageId)
                .orElseThrow(() -> new ResourceNotFoundException("Mortgage " + mortgageId + " not found"));
    }

    /** LTV recomputed against the account's original sanctioned amount, not outstanding balance — adjust here if the product wants LTV tracked against current outstanding principal instead. */
    private BigDecimal ltvRatio(BigDecimal referenceAmount, BigDecimal valuationAmount) {
        return referenceAmount.multiply(BigDecimal.valueOf(100)).divide(valuationAmount, 2, RoundingMode.HALF_UP);
    }
}
