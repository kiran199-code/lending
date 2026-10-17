package com.bank.lending.accounting;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.accounting.dto.AccountingDtos.ExceptionRow;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.DraftLine;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.model.PostingException;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.CarryRepository;
import com.bank.lending.accounting.repository.ExceptionRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.accounting.repository.SubledgerRepository;
import com.bank.lending.accounting.service.AccountingEventService;
import com.bank.lending.accounting.service.AccountingPoster;
import com.bank.lending.accounting.service.PostResult;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.PenalInterest;
import com.bank.lending.payment.domain.EmiTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real poster, event service and engine with the seed templates, over in-memory repositories (no database, no
 * Spring). Each test plays a stretch of a loan's life and checks the ledger against what the loan sub-ledger would
 * say: balances by role, every journal balanced, and the receivables settling to exactly zero.
 */
class AccountingFlowTest {

    private static final BigDecimal DAILY = new BigDecimal("32.8767"); // 100,000 at 12% / 365
    private static final long ACCOUNT = 1L;
    private static final LocalDate DISBURSED = LocalDate.of(2026, 10, 1);
    private static final LocalDate DUE1 = LocalDate.of(2026, 10, 31);

    // ----------------------------------------------------------------------------------------------- fakes

    /** Keeps journals in memory: net debit per ledger and role, and the totals of every ledger. */
    static class FakeJournals extends JournalRepository {
        final Map<String, Long> keys = new HashMap<>();
        final Map<String, BigDecimal> netDebit = new HashMap<>();
        final Map<Integer, BigDecimal[]> totals = new HashMap<>();
        final List<DraftJournal> journals = new ArrayList<>();
        long seq;

        FakeJournals() {
            super(null);
        }

        @Override
        public long nextJournalId() {
            return ++seq;
        }

        @Override
        public boolean claimKey(String key, long journalId, LocalDate businessDate) {
            return keys.putIfAbsent(key, journalId) == null;
        }

        @Override
        public Optional<Stored> findByKey(String key) {
            Long id = keys.get(key);
            return id == null ? Optional.empty() : Optional.of(new Stored(id, journalNo(id), DISBURSED));
        }

        @Override
        public void markNoJournal(String key) {
            keys.put(key, 0L);
        }

        @Override
        public String periodStatus(LocalDate date) {
            return "OPEN";
        }

        @Override
        public void insert(long journalId, PostingRequest req, DraftJournal draft, String postingMode, boolean backdated,
                           boolean priorPeriod, Long reversalOf, String stage) {
            journals.add(draft);
            for (DraftLine l : draft.lines()) {
                BigDecimal signed = "DR".equals(l.side()) ? l.amountBase() : l.amountBase().negate();
                netDebit.merge(l.ledgerId() + "/" + l.roleCode(), signed, BigDecimal::add);
                BigDecimal[] t = totals.computeIfAbsent(l.ledgerId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                t["DR".equals(l.side()) ? 0 : 1] = t["DR".equals(l.side()) ? 0 : 1].add(l.amountBase());
            }
        }

        @Override
        public BigDecimal netDebit(long accountId, int ledgerId, String roleCode) {
            return netDebit.getOrDefault(ledgerId + "/" + roleCode, BigDecimal.ZERO);
        }

        BigDecimal debit(String role) {
            return netDebit.getOrDefault("1/" + role, BigDecimal.ZERO);
        }

        BigDecimal credit(String role) {
            return debit(role).negate();
        }
    }

    static class FakeCarries extends CarryRepository {
        final Map<String, State> states = new HashMap<>();

        FakeCarries() {
            super(null);
        }

        @Override
        public State lock(long accountId, String stream, long refId) {
            return states.getOrDefault(accountId + "/" + stream + "/" + refId, new State(BigDecimal.ZERO, BigDecimal.ZERO));
        }

        @Override
        public void save(long accountId, String stream, long refId, BigDecimal cumulativeAccrued, BigDecimal cumulativePosted) {
            states.put(accountId + "/" + stream + "/" + refId, new State(cumulativeAccrued, cumulativePosted));
        }
    }

    static class FakeExceptions extends ExceptionRepository {
        final List<String> recorded = new ArrayList<>();

        FakeExceptions() {
            super(null);
        }

        @Override
        public void record(String eventCode, Long accountId, String key, String payloadJson, String reason,
                           String message, boolean eodGate) {
            recorded.add(eventCode + ":" + reason);
        }

        @Override
        public List<ExceptionRow> list(String status, int limit, int offset) {
            return List.of();
        }
    }

    static class FakeConfig extends AccountingConfigRepository {
        final AccountingConfig config;

        FakeConfig(AccountingConfig config, AccountingSettings settings) {
            super(null, settings);
            this.config = config;
        }

        @Override
        public AccountingConfig current() {
            return config;
        }
    }

    /** Interest the accrual table would hold: one raw amount per date. */
    static class FakeSubledger extends SubledgerRepository {
        final Map<LocalDate, BigDecimal> accruals = new LinkedHashMap<>();

        FakeSubledger() {
            super(null);
        }

        @Override
        public BigDecimal accruedAfter(long accountId, LocalDate after) {
            return accruals.entrySet().stream().filter(e -> e.getKey().isAfter(after)).map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    static class FakeDates extends BusinessDateService {
        LocalDate today = DISBURSED;

        FakeDates() {
            super(null, null, null, null, "SATURDAY,SUNDAY", true, false, "Asia/Kolkata");
        }

        @Override
        public LocalDate currentDate() {
            return today;
        }
    }

    static class TestEvents extends AccountingEventService {
        TestEvents(AccountingConfigRepository c, JournalRepository j, CarryRepository k, ExceptionRepository e,
                   AccountingSettings s) {
            super(c, j, k, e, s, new ObjectMapper().findAndRegisterModules(), null);
        }

        @Override
        protected <T> T runNested(Supplier<T> work) {
            return work.get();
        }
    }

    /** One wired-up instance per test. */
    static class Rig {
        final AccountingSettings settings = new AccountingSettings(true, "INR", new BigDecimal("0.01"), 30);
        final FakeJournals journals = new FakeJournals();
        final FakeCarries carries = new FakeCarries();
        final FakeExceptions exceptions = new FakeExceptions();
        final FakeSubledger subledger = new FakeSubledger();
        final FakeDates dates = new FakeDates();
        final AccountingPoster poster;
        final LoanAccount account = LoanAccount.builder().accountId(ACCOUNT).productCode("HL")
                .accountStatus(LoanAccount.AccountStatus.ACTIVE).build();

        Rig() {
            this(SeedFixtures.config());
        }

        Rig(AccountingConfig config) {
            FakeConfig cfg = new FakeConfig(config, settings);
            TestEvents events = new TestEvents(cfg, journals, carries, exceptions, settings);
            poster = new AccountingPoster(events, settings, cfg, journals, subledger, dates);
        }

        void disburse(String amount) {
            dates.today = DISBURSED;
            poster.disbursed(account, new BigDecimal(amount), BigDecimal.ZERO);
        }

        /** Accrues {@code days} days starting the day after {@code from}. */
        void accrue(LocalDate from, int days) {
            for (int i = 1; i <= days; i++) {
                LocalDate d = from.plusDays(i);
                dates.today = d;
                subledger.accruals.put(d, DAILY);
                poster.interestAccrued(account, d, DAILY);
            }
        }

        LoanDemand demand(long id, int no, LocalDate due, String principal, String interest) {
            return LoanDemand.builder().demandId(id).installmentNumber(no).dueDate(due)
                    .principalDue(new BigDecimal(principal)).interestDue(new BigDecimal(interest))
                    .status(LoanDemand.DemandStatus.OPEN).build();
        }

        EmiTransaction txn(long id, String amount, LocalDate valueDate) {
            return EmiTransaction.builder().transactionId(id).amount(new BigDecimal(amount)).valueDate(valueDate).build();
        }
    }

    private static void assertAllBalanced(FakeJournals j) {
        assertThat(j.journals.isEmpty()).isFalse();
        for (DraftJournal d : j.journals) {
            BigDecimal dr = BigDecimal.ZERO;
            BigDecimal cr = BigDecimal.ZERO;
            Map<Integer, BigDecimal[]> perLedger = new HashMap<>();
            for (DraftLine l : d.lines()) {
                BigDecimal[] t = perLedger.computeIfAbsent(l.ledgerId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                t["DR".equals(l.side()) ? 0 : 1] = t["DR".equals(l.side()) ? 0 : 1].add(l.amountBase());
            }
            for (BigDecimal[] t : perLedger.values()) {
                assertThat(t[0]).isEqualByComparingTo(t[1]);
            }
        }
        for (BigDecimal[] t : j.totals.values()) {
            assertThat(t[0]).isEqualByComparingTo(t[1]);
        }
    }

    // ----------------------------------------------------------------------------------------------- scenarios

    @Test
    void aFirstInstalmentFromDisbursementToPaymentLeavesEveryReceivableAtZero() {
        Rig r = new Rig();
        r.disburse("100000.00");
        assertThat(r.journals.debit("LOAN_PRINCIPAL")).isEqualByComparingTo("100000.00");
        assertThat(r.journals.credit("DISBURSAL_CLEARING")).isEqualByComparingTo("100000.00");

        r.accrue(DISBURSED, 30); // 2 Oct .. 31 Oct: 30 x 32.8767 = 986.301
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("986.30");
        assertThat(r.journals.credit("INT_INCOME")).isEqualByComparingTo("986.30");

        r.dates.today = DUE1;
        LoanDemand d = r.demand(11, 1, DUE1, "8000.00", "986.30");
        r.poster.demandsRaised(r.account, List.of(d));
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("0.00");
        assertThat(r.journals.debit("INT_DUE_RECEIVABLE")).isEqualByComparingTo("986.30");
        assertThat(r.journals.debit("PRINCIPAL_DUE")).isEqualByComparingTo("8000.00");
        assertThat(r.journals.debit("LOAN_PRINCIPAL")).isEqualByComparingTo("92000.00");
        assertThat(r.journals.credit("INT_INCOME")).isEqualByComparingTo("986.30"); // no true-up was needed

        r.poster.emiPaid(r.account, r.txn(21, "8986.30", DUE1), BigDecimal.ZERO, new BigDecimal("986.30"),
                new BigDecimal("8000.00"), BigDecimal.ZERO);
        assertThat(r.journals.debit("INT_DUE_RECEIVABLE")).isEqualByComparingTo("0.00");
        assertThat(r.journals.debit("PRINCIPAL_DUE")).isEqualByComparingTo("0.00");
        assertThat(r.journals.debit("CUSTOMER_SETTLEMENT")).isEqualByComparingTo("8986.30");
        assertAllBalanced(r.journals);
    }

    @Test
    void interestThatWasNeverAccruedIsRecognisedWhenTheDemandIsRaised() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 20); // the daily job missed ten days: 657.53 accrued
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("657.53");

        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));

        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("0.00"); // never negative
        assertThat(r.journals.debit("INT_DUE_RECEIVABLE")).isEqualByComparingTo("986.30");
        assertThat(r.journals.credit("INT_INCOME")).isEqualByComparingTo("986.30"); // 657.53 + true-up 328.77
        assertAllBalanced(r.journals);
    }

    @Test
    void accrualBeyondTheDueDateStaysAsNextPeriodsAccrual() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 35); // up to 5 Nov, the demand is raised late: 35 x 32.8767 = 1150.6845
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("1150.68");

        r.dates.today = LocalDate.of(2026, 11, 5);
        // the schedule says 986.29 for the instalment, a paisa less than was accrued up to its due date
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.29")));

        // five days after the due date remain accrued (164.38); the paisa is trued down to income
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("164.38");
        assertThat(r.journals.debit("INT_DUE_RECEIVABLE")).isEqualByComparingTo("986.29");
        assertThat(r.journals.credit("INT_INCOME")).isEqualByComparingTo("1150.67");
        assertAllBalanced(r.journals);
    }

    @Test
    void aDemandThatNeededNoTrueUpPostsNoTrueUpJournal() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 30);
        int before = r.journals.journals.size();
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));
        assertThat(r.journals.journals.size()).isEqualTo(before + 1); // only DEMAND_RAISE
        assertThat(r.journals.journals.get(before).eventCode()).isEqualTo("DEMAND_RAISE");
    }

    @Test
    void aClearedDemandIsNotJournalled() {
        Rig r = new Rig();
        r.disburse("100000.00");
        LoanDemand legacy = LoanDemand.builder().demandId(5L).installmentNumber(1).dueDate(DUE1)
                .principalDue(new BigDecimal("8000.00")).interestDue(new BigDecimal("986.30"))
                .status(LoanDemand.DemandStatus.CLEARED).build();
        int before = r.journals.journals.size();
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(legacy));
        assertThat(r.journals.journals.size()).isEqualTo(before);
    }

    @Test
    void penalInterestIsRoundedPerDemandAndGoesToSuspenseOnceTheAccountIsSubstandard() {
        Rig r = new Rig();
        r.disburse("100000.00");
        // 10 days at 1.2345 on demand 11 (12.345 -> 12.35) and 4 days at 0.5 on demand 12 (2.00)
        for (int i = 1; i <= 10; i++) {
            LocalDate d = DUE1.plusDays(i);
            r.dates.today = d;
            r.poster.penalAccrued(r.account, List.of(penal(100 + i, 11, d, "1.2345"),
                    penal(200 + i, 12, d, i <= 4 ? "0.5000" : "0.0000")));
        }
        assertThat(r.journals.debit("PENAL_RECEIVABLE")).isEqualByComparingTo("14.35");
        assertThat(r.journals.credit("PENAL_INCOME")).isEqualByComparingTo("14.35");

        LoanAccount substandard = LoanAccount.builder().accountId(ACCOUNT).productCode("HL")
                .accountStatus(LoanAccount.AccountStatus.NPA_SUBSTANDARD).build();
        r.dates.today = LocalDate.of(2026, 11, 12);
        r.poster.penalAccrued(substandard, List.of(penal(300, 11, LocalDate.of(2026, 11, 12), "10.0000")));
        assertThat(r.journals.credit("PENAL_SUSPENSE")).isEqualByComparingTo("10.00");
        assertThat(r.journals.credit("PENAL_INCOME")).isEqualByComparingTo("14.35");
        assertAllBalanced(r.journals);
    }

    private static PenalInterest penal(long id, long demandId, LocalDate date, String amount) {
        return PenalInterest.builder().penalId(id).demandId(demandId).businessDate(date).amount(new BigDecimal(amount)).build();
    }

    @Test
    void foreclosureOfAnAccountWithAccruedInterestAndAnAdvanceClosesEverything() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 30);
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));
        r.poster.emiPaid(r.account, r.txn(21, "9486.30", DUE1), BigDecimal.ZERO, new BigDecimal("986.30"),
                new BigDecimal("8000.00"), new BigDecimal("500.00"));
        assertThat(r.journals.credit("ADVANCE_LIABILITY")).isEqualByComparingTo("500.00");

        r.accrue(DUE1, 10); // 1 Nov .. 10 Nov: 328.767 -> 328.77
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("328.77");

        LocalDate fc = LocalDate.of(2026, 11, 10);
        // owed = 92000 principal + 328.77 accrued = 92328.77; the 500 advance is used, 91828.77 is paid
        r.poster.foreclosed(r.account, r.txn(31, "91828.77", fc), new BigDecimal("91828.77"), new BigDecimal("500.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("92000.00"), new BigDecimal("328.77"));

        for (String role : List.of("LOAN_PRINCIPAL", "PRINCIPAL_DUE", "INT_ACCRUED_RECEIVABLE", "INT_DUE_RECEIVABLE",
                "PENAL_RECEIVABLE", "ADVANCE_LIABILITY")) {
            assertThat(r.journals.debit(role)).isEqualByComparingTo("0.00");
        }
        assertAllBalanced(r.journals);
    }

    @Test
    void foreclosureTruesTheReceivableWhenTheJournalAndTheSubledgerDifferByAPaisa() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 30);
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));
        r.poster.emiPaid(r.account, r.txn(21, "8986.30", DUE1), BigDecimal.ZERO, new BigDecimal("986.30"),
                new BigDecimal("8000.00"), BigDecimal.ZERO);
        r.accrue(DUE1, 10);
        // the payoff quotes 328.78 of accrued interest although 328.77 was journalled
        r.poster.foreclosed(r.account, r.txn(31, "92328.78", LocalDate.of(2026, 11, 10)), new BigDecimal("92328.78"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("92000.00"),
                new BigDecimal("328.78"));
        assertThat(r.journals.debit("INT_ACCRUED_RECEIVABLE")).isEqualByComparingTo("0.00");
        assertThat(r.journals.debit("LOAN_PRINCIPAL")).isEqualByComparingTo("0.00");
        assertAllBalanced(r.journals);
    }

    @Test
    void anAdvanceIsHeldThenAppliedToTheNextDemand() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 30);
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));
        r.poster.emiPaid(r.account, r.txn(21, "9486.30", DUE1), BigDecimal.ZERO, new BigDecimal("986.30"),
                new BigDecimal("8000.00"), new BigDecimal("500.00"));
        assertThat(r.journals.credit("ADVANCE_LIABILITY")).isEqualByComparingTo("500.00");

        r.accrue(DUE1, 30);
        LocalDate due2 = LocalDate.of(2026, 11, 30);
        r.dates.today = due2;
        r.poster.demandsRaised(r.account, List.of(r.demand(12, 2, due2, "8100.00", "960.00")));
        PostResult applied = r.poster.advanceApplied(r.account, due2, 7L, BigDecimal.ZERO, new BigDecimal("500.00"),
                BigDecimal.ZERO);
        assertThat(applied.journalled()).isTrue();
        assertThat(r.journals.credit("ADVANCE_LIABILITY")).isEqualByComparingTo("0.00");
        assertThat(r.journals.debit("INT_DUE_RECEIVABLE")).isEqualByComparingTo("460.00");
        assertAllBalanced(r.journals);
    }

    @Test
    void aPartPaymentReducesPrincipalThatIsNotYetDue() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.accrue(DISBURSED, 30);
        r.dates.today = DUE1;
        r.poster.demandsRaised(r.account, List.of(r.demand(11, 1, DUE1, "8000.00", "986.30")));
        // 8986.30 clears the demand and 20000 prepays principal
        r.poster.partPaid(r.account, r.txn(22, "28986.30", DUE1), BigDecimal.ZERO, new BigDecimal("986.30"),
                new BigDecimal("8000.00"), new BigDecimal("20000.00"));
        assertThat(r.journals.debit("LOAN_PRINCIPAL")).isEqualByComparingTo("72000.00");
        assertThat(r.journals.debit("CUSTOMER_SETTLEMENT")).isEqualByComparingTo("28986.30");
        assertAllBalanced(r.journals);
    }

    @Test
    void theSameBusinessEventIsNeverJournalledTwice() {
        Rig r = new Rig();
        r.disburse("100000.00");
        r.dates.today = DUE1;
        PostResult first = r.poster.emiPaid(r.account, r.txn(21, "100.00", DUE1), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("100.00"));
        PostResult again = r.poster.emiPaid(r.account, r.txn(21, "100.00", DUE1), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("100.00"));
        assertThat(first.journalled()).isTrue();
        assertThat(again.status()).isEqualTo(PostResult.Status.DUPLICATE);
        assertThat(r.journals.credit("ADVANCE_LIABILITY")).isEqualByComparingTo("100.00");
    }

    @Test
    void aFailedNonBlockingAccrualGoesToTheExceptionQueueAndTheBusinessChangeCarriesOn() {
        AccountingConfig seed = SeedFixtures.config();
        List<com.bank.lending.accounting.model.Defs.RouteDef> routes = seed.routes().stream()
                .filter(x -> !"INT_ACCRUED_RECEIVABLE".equals(x.roleCode())).toList();
        AccountingConfig broken = new AccountingConfig(seed.events(), seed.templatesByEvent(), seed.ledgers(),
                seed.accounts(), routes, seed.eventLedgers());
        Rig r = new Rig(broken);
        PostResult result = r.poster.interestAccrued(r.account, DISBURSED.plusDays(1), DAILY);
        assertThat(result.status()).isEqualTo(PostResult.Status.FAILED_RECORDED);
        assertThat(r.exceptions.recorded).hasSize(1);
        assertThat(r.journals.journals.isEmpty()).isTrue();
    }

    @Test
    void aFailedBlockingEventStopsTheBusinessChange() {
        AccountingConfig seed = SeedFixtures.config();
        List<com.bank.lending.accounting.model.Defs.RouteDef> routes = seed.routes().stream()
                .filter(x -> !"DISBURSAL_CLEARING".equals(x.roleCode())).toList();
        AccountingConfig broken = new AccountingConfig(seed.events(), seed.templatesByEvent(), seed.ledgers(),
                seed.accounts(), routes, seed.eventLedgers());
        Rig r = new Rig(broken);
        assertThatThrownBy(() -> r.poster.disbursed(r.account, new BigDecimal("1000.00"), BigDecimal.ZERO))
                .isInstanceOf(PostingException.class).hasMessageContaining("DISBURSAL_CLEARING");
    }

    @Test
    void switchingAccountingOffPostsNothing() {
        Rig r = new Rig();
        AccountingSettings off = new AccountingSettings(false, "INR", new BigDecimal("0.01"), 30);
        FakeConfig cfg = new FakeConfig(SeedFixtures.config(), off);
        AccountingPoster poster = new AccountingPoster(new TestEvents(cfg, r.journals, r.carries, r.exceptions, off), off,
                cfg, r.journals, r.subledger, r.dates);
        PostResult result = poster.disbursed(r.account, new BigDecimal("1000.00"), BigDecimal.ZERO);
        assertThat(result.status()).isEqualTo(PostResult.Status.SKIPPED);
        assertThat(r.journals.journals.isEmpty()).isTrue();
    }
}
