package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.SecurityRule;
import com.bank.lending.chit.domain.rules.ChitRules;

import java.math.BigDecimal;

/**
 * What one ticket owes at enrolment, worked out from the group's rules, and the check that what was received matches it.
 *
 * <ul>
 *   <li>{@code enrolmentFee} and {@code registrationFee} come straight from the rules.</li>
 *   <li>DEPOSIT: {@code securityDeposit} = securityDepositPct % of the chit value, received in cash.</li>
 *   <li>LIEN_ON_DEPOSIT: {@code lienAmount} is the same figure, but no cash moves; the subscriber's own deposit is
 *       marked under lien and its reference is recorded.</li>
 *   <li>GUARANTOR: a guarantor (a different customer) is named; no money.</li>
 * </ul>
 * Amounts must match exactly: a short or an excess payment is refused rather than guessed at.
 */
public record EnrolmentQuote(BigDecimal enrolmentFee, BigDecimal registrationFee, BigDecimal securityDeposit,
                             BigDecimal lienAmount, boolean guarantorRequired, BigDecimal cashDue) {

    /** What the caller says was received. Null amounts count as zero. */
    public record Received(BigDecimal enrolmentFee, BigDecimal registrationFee, BigDecimal securityDeposit,
                           Long guarantorCustomerId, String lienReference) {
    }

    public static EnrolmentQuote from(ChitRules rules) {
        BigDecimal enrol = Money.scale(Money.nz(rules.enrolmentFee()));
        BigDecimal reg = Money.scale(Money.nz(rules.registrationFee()));
        SecurityRule rule = rules.securityRule() == null ? SecurityRule.NONE : rules.securityRule();
        BigDecimal security = rule == SecurityRule.DEPOSIT || rule == SecurityRule.LIEN_ON_DEPOSIT
                ? Money.percentOf(rules.chitValue(), Money.nz(rules.securityDepositPct()))
                : Money.ZERO;
        BigDecimal deposit = rule == SecurityRule.DEPOSIT ? security : Money.ZERO;
        BigDecimal lien = rule == SecurityRule.LIEN_ON_DEPOSIT ? security : Money.ZERO;
        return new EnrolmentQuote(enrol, reg, deposit, lien, rule == SecurityRule.GUARANTOR,
                Money.scale(enrol.add(reg).add(deposit)));
    }

    /** Refuses the enrolment unless the received amounts, guarantor and lien reference match this quote. */
    public void check(Received r, long subscriberCustomerId) {
        mustMatch("enrolmentFee", enrolmentFee, r.enrolmentFee());
        mustMatch("registrationFee", registrationFee, r.registrationFee());
        mustMatch("securityDeposit", securityDeposit, r.securityDeposit());
        if (guarantorRequired) {
            if (r.guarantorCustomerId() == null) {
                throw new ChitException("GUARANTOR_REQUIRED", "This group requires a guarantor for every ticket");
            }
            if (r.guarantorCustomerId() == subscriberCustomerId) {
                throw new ChitException("GUARANTOR_IS_SUBSCRIBER", "The guarantor must be a different customer from the subscriber");
            }
        } else if (r.guarantorCustomerId() != null) {
            throw new ChitException("GUARANTOR_NOT_APPLICABLE", "This group does not take a guarantor");
        }
        boolean lienRequired = Money.isPositive(lienAmount);
        boolean hasRef = r.lienReference() != null && !r.lienReference().isBlank();
        if (lienRequired && !hasRef) {
            throw new ChitException("LIEN_REFERENCE_REQUIRED", "Give the reference of the deposit placed under lien");
        }
        if (!lienRequired && hasRef) {
            throw new ChitException("LIEN_NOT_APPLICABLE", "This group does not take a lien on a deposit");
        }
    }

    private static void mustMatch(String field, BigDecimal due, BigDecimal received) {
        BigDecimal got = Money.nz(received);
        if (due.compareTo(got) != 0) {
            throw new ChitException("ENROLMENT_AMOUNT_MISMATCH", field + " received is " + got.toPlainString()
                    + " but " + due.toPlainString() + " is due");
        }
    }
}
