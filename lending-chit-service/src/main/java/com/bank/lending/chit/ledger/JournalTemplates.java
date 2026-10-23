package com.bank.lending.chit.ledger;

import com.bank.lending.chit.calc.AuctionSettlement;
import com.bank.lending.chit.common.ChitException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;

import static com.bank.lending.chit.ledger.AccountRef.of;
import static com.bank.lending.chit.ledger.AccountType.*;

/**
 * Every journal the service posts, as a pure function from the business event to balanced lines. No database, no
 * clock: the caller supplies the posting key (unique per business event) and the amounts.
 *
 * <p>Core templates (section 12 of the prompt): demand, payment, auction settled, dividend applied, prize disbursed,
 * closure sweep. The remaining templates were proposed in BUILD_STATUS.md and need Kiran's review before Phase 5 to
 * 7 use them; they are marked REVIEW. Exit refund is not here: it waits for open question 3.
 */
public final class JournalTemplates {

    private JournalTemplates() {
    }

    // ---- collections ----------------------------------------------------------------------------------------------

    /** The instalment falls due: the subscriber owes the pool. Dr SUBSCRIBER_RECEIVABLE[t] / Cr CHIT_POOL. */
    public static JournalDraft demand(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("DEMAND", key).narration("Instalment demanded")
                .debit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount)
                .credit(of(CHIT_POOL), amount).build();
    }

    /** Money received from a subscriber. Dr POOL_CASH / Cr SUBSCRIBER_RECEIVABLE[t]. */
    public static JournalDraft payment(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("PAYMENT", key).narration("Payment received")
                .debit(of(POOL_CASH), amount)
                .credit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount).build();
    }

    /** A late fee is charged: the subscriber owes it, the foreman earns it. Dr SUBSCRIBER_RECEIVABLE[t] / Cr LATE_FEE_INCOME. */
    public static JournalDraft lateFee(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("LATE_FEE", key).narration("Late fee charged")
                .debit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount)
                .credit(of(LATE_FEE_INCOME), amount).build();
    }

    // ---- the auction ----------------------------------------------------------------------------------------------

    /**
     * The auction is settled and the pot (P) is split: Dr CHIT_POOL P / Cr PRIZE_PAYABLE W, COMMISSION_INCOME C and
     * DIVIDEND_PAYABLE[t] for each share. P = W + C + dividend pool always holds, so the journal balances by itself.
     *
     * @param dividendByTicketId each beneficiary's share, keyed by ticket id (the settlement is keyed by ticket number)
     */
    public static JournalDraft auctionSettled(String key, AuctionSettlement s, Map<Long, BigDecimal> dividendByTicketId) {
        BigDecimal shares = dividendByTicketId.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shares.compareTo(s.dividendPool()) != 0) {
            throw new ChitException("DIVIDEND_SHARES_MISMATCH",
                    "Dividend shares " + shares.toPlainString() + " do not add up to the dividend pool " + s.dividendPool().toPlainString());
        }
        JournalDraft.Builder b = JournalDraft.builder("AUCTION_SETTLED", key).narration("Auction settled")
                .debit(of(CHIT_POOL), s.chitValue())
                .credit(of(PRIZE_PAYABLE), s.prize())
                .credit(of(COMMISSION_INCOME), s.commission());
        for (Map.Entry<Long, BigDecimal> e : new java.util.TreeMap<>(dividendByTicketId).entrySet()) {
            b.credit(of(DIVIDEND_PAYABLE, e.getKey()), e.getValue());
        }
        return b.build();
    }

    /** Dividend used to reduce the next instalment (ADJUST_NEXT_INSTALMENT). Dr DIVIDEND_PAYABLE[t] / Cr SUBSCRIBER_RECEIVABLE[t]. */
    public static JournalDraft dividendApplied(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("DIVIDEND_APPLIED", key).narration("Dividend set against the instalment")
                .debit(of(DIVIDEND_PAYABLE, ticketId), amount)
                .credit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount).build();
    }

    /** Dividend paid out in cash (PAY_OUT). Dr DIVIDEND_PAYABLE[t] / Cr POOL_CASH. */
    public static JournalDraft dividendPaidOut(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("DIVIDEND_PAID", key).narration("Dividend paid out")
                .debit(of(DIVIDEND_PAYABLE, ticketId), amount)
                .credit(of(POOL_CASH), amount).build();
    }

    /** A dividend that was never claimed moves to UNCLAIMED. Dr DIVIDEND_PAYABLE[t] / Cr UNCLAIMED. */
    public static JournalDraft dividendForfeited(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("DIVIDEND_FORFEITED", key).narration("Dividend moved to unclaimed")
                .debit(of(DIVIDEND_PAYABLE, ticketId), amount)
                .credit(of(UNCLAIMED), amount).build();
    }

    // ---- the prize -------------------------------------------------------------------------------------------------

    /**
     * The prize leaves the pool. Dr PRIZE_PAYABLE gross / Cr POOL_CASH gross. When part of the prize is held back as
     * security (hold &gt; 0) the held part stays in the group: Dr SECURITY_CASH hold / Cr SECURITY_DEPOSIT_LIABILITY[t] hold.
     * REVIEW for the hold part.
     */
    public static JournalDraft prizeDisbursed(String key, long ticketId, BigDecimal gross, BigDecimal hold) {
        BigDecimal h = hold == null ? BigDecimal.ZERO : hold;
        if (h.signum() < 0 || h.compareTo(gross) > 0) {
            throw new ChitException("HOLD_INVALID", "Hold must be between 0 and the gross prize");
        }
        return JournalDraft.builder("PRIZE_DISBURSED", key).narration("Prize disbursed")
                .debit(of(PRIZE_PAYABLE), gross)
                .debit(of(SECURITY_CASH), h)
                .credit(of(POOL_CASH), gross)
                .credit(of(SECURITY_DEPOSIT_LIABILITY, ticketId), h).build();
    }

    // ---- closure ---------------------------------------------------------------------------------------------------

    /** At closure the cash left in the pool goes to the foreman. Dr FOREMAN_CASH / Cr POOL_CASH. */
    public static JournalDraft closureSweep(String key, BigDecimal residual) {
        return JournalDraft.builder("CLOSURE_SWEEP", key).narration("Residual pool cash swept to the foreman")
                .debit(of(FOREMAN_CASH), residual)
                .credit(of(POOL_CASH), residual).build();
    }

    // ---- fees and deposits (REVIEW) --------------------------------------------------------------------------------

    /** Enrolment fee received by the foreman. Dr FOREMAN_CASH / Cr ENROLMENT_FEE_INCOME. */
    public static JournalDraft enrolmentFee(String key, BigDecimal amount) {
        return JournalDraft.builder("ENROLMENT_FEE", key).narration("Enrolment fee received")
                .debit(of(FOREMAN_CASH), amount)
                .credit(of(ENROLMENT_FEE_INCOME), amount).build();
    }

    /** Security deposit received. Dr SECURITY_CASH / Cr SECURITY_DEPOSIT_LIABILITY[t]. */
    public static JournalDraft securityReceived(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("SECURITY_RECEIVED", key).narration("Security deposit received")
                .debit(of(SECURITY_CASH), amount)
                .credit(of(SECURITY_DEPOSIT_LIABILITY, ticketId), amount).build();
    }

    /** Cancellation: the enrolment fee is refunded. Dr ENROLMENT_FEE_INCOME / Cr FOREMAN_CASH. REVIEW. */
    public static JournalDraft enrolmentFeeRefund(String key, BigDecimal amount) {
        return JournalDraft.builder("ENROLMENT_FEE_REFUND", key).narration("Enrolment fee refunded")
                .debit(of(ENROLMENT_FEE_INCOME), amount)
                .credit(of(FOREMAN_CASH), amount).build();
    }

    /** Cancellation: the deposit is returned. Dr SECURITY_DEPOSIT_LIABILITY[t] / Cr SECURITY_CASH. REVIEW. */
    public static JournalDraft securityRefund(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("SECURITY_REFUND", key).narration("Security deposit returned")
                .debit(of(SECURITY_DEPOSIT_LIABILITY, ticketId), amount)
                .credit(of(SECURITY_CASH), amount).build();
    }

    /** Ticket transfer fee. Dr FOREMAN_CASH / Cr TRANSFER_FEE_INCOME. REVIEW. */
    public static JournalDraft transferFee(String key, BigDecimal amount) {
        return JournalDraft.builder("TRANSFER_FEE", key).narration("Ticket transfer fee")
                .debit(of(FOREMAN_CASH), amount)
                .credit(of(TRANSFER_FEE_INCOME), amount).build();
    }

    // ---- defaults, guarantees and liquidity (REVIEW) ---------------------------------------------------------------

    /**
     * The held deposit is applied to a prized defaulter's arrears, and its cash moves from the deposit account to the
     * pool. Dr SECURITY_DEPOSIT_LIABILITY[t] and POOL_CASH / Cr SUBSCRIBER_RECEIVABLE[t] and SECURITY_CASH.
     */
    public static JournalDraft securityApplied(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("SECURITY_APPLIED", key).narration("Deposit applied to arrears")
                .debit(of(SECURITY_DEPOSIT_LIABILITY, ticketId), amount)
                .debit(of(POOL_CASH), amount)
                .credit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount)
                .credit(of(SECURITY_CASH), amount).build();
    }

    /**
     * The foreman pays a shortfall into the pool and takes over the claim on the ticket.
     * Dr POOL_CASH and FOREMAN_GUARANTEE_RECEIVABLE[t] / Cr FOREMAN_CASH and SUBSCRIBER_RECEIVABLE[t].
     */
    public static JournalDraft foremanCovers(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("FOREMAN_COVERS", key).narration("Foreman covered a shortfall")
                .debit(of(POOL_CASH), amount)
                .debit(of(FOREMAN_GUARANTEE_RECEIVABLE, ticketId), amount)
                .credit(of(FOREMAN_CASH), amount)
                .credit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount).build();
    }

    /** The ticket repays what the foreman guaranteed. Dr FOREMAN_CASH / Cr FOREMAN_GUARANTEE_RECEIVABLE[t]. */
    public static JournalDraft guaranteeRepaid(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("GUARANTEE_REPAID", key).narration("Guaranteed amount repaid to the foreman")
                .debit(of(FOREMAN_CASH), amount)
                .credit(of(FOREMAN_GUARANTEE_RECEIVABLE, ticketId), amount).build();
    }

    /** The foreman bears the loss of a guarantee. Dr GUARANTEE_LOSS_EXPENSE / Cr FOREMAN_GUARANTEE_RECEIVABLE[t]. */
    public static JournalDraft guaranteeWrittenOff(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("GUARANTEE_WRITTEN_OFF", key).narration("Guarantee written off, foreman bears the loss")
                .debit(of(GUARANTEE_LOSS_EXPENSE), amount)
                .credit(of(FOREMAN_GUARANTEE_RECEIVABLE, ticketId), amount).build();
    }

    /**
     * The pool bears the loss of a receivable written off. Dr WRITE_OFF_LOSS / Cr SUBSCRIBER_RECEIVABLE[t].
     * Who finally absorbs this loss is open question 4; the journal itself does not depend on the answer.
     */
    public static JournalDraft receivableWrittenOff(String key, long ticketId, BigDecimal amount) {
        return JournalDraft.builder("RECEIVABLE_WRITTEN_OFF", key).narration("Receivable written off")
                .debit(of(WRITE_OFF_LOSS), amount)
                .credit(of(SUBSCRIBER_RECEIVABLE, ticketId), amount).build();
    }

    /** The foreman floats a liquidity gap so the prize can be paid. Dr POOL_CASH / Cr FOREMAN_FLOAT_PAYABLE. */
    public static JournalDraft foremanFloat(String key, BigDecimal amount) {
        return JournalDraft.builder("FOREMAN_FLOAT", key).narration("Foreman floated a liquidity gap")
                .debit(of(POOL_CASH), amount)
                .credit(of(FOREMAN_FLOAT_PAYABLE), amount).build();
    }

    /** The float is repaid from later collections. Dr FOREMAN_FLOAT_PAYABLE / Cr POOL_CASH. */
    public static JournalDraft floatRepaid(String key, BigDecimal amount) {
        return JournalDraft.builder("FLOAT_REPAID", key).narration("Foreman float repaid")
                .debit(of(FOREMAN_FLOAT_PAYABLE), amount)
                .credit(of(POOL_CASH), amount).build();
    }

    /** Dividend shares keyed by ticket number (as the settlement gives them) to shares keyed by ticket id. */
    public static Map<Long, BigDecimal> dividendsByTicketId(SortedMap<Integer, BigDecimal> byNo, Map<Integer, Long> ticketIdByNo) {
        java.util.TreeMap<Long, BigDecimal> out = new java.util.TreeMap<>();
        for (Map.Entry<Integer, BigDecimal> e : byNo.entrySet()) {
            Long id = ticketIdByNo.get(e.getKey());
            if (id == null) {
                throw new ChitException("TICKET_UNKNOWN", "No ticket id for ticket number " + e.getKey());
            }
            out.put(id, e.getValue());
        }
        return out;
    }
}
