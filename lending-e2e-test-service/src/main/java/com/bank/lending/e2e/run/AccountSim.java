package com.bank.lending.e2e.run;

import com.bank.lending.e2e.calc.ScheduleInvariants;
import com.bank.lending.e2e.scenario.Scenario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Everything the suite remembers about one test account: its own ledger, independent of what the system reports. */
public final class AccountSim {

    public final int index;
    public final Scenario scenario;
    public final long customerId;
    public final long sanctionRefId;

    public Long accountId;
    public String accountNumber;
    public ScheduleInvariants.Terms terms;
    public List<ScheduleInvariants.Row> rows = new ArrayList<>();

    // test ledger
    public final Set<Integer> paid = new LinkedHashSet<>();
    public final Map<Integer, LocalDate> paidOn = new TreeMap<>();
    public final Map<Integer, BigDecimal> paidAmount = new TreeMap<>();
    public BigDecimal principalPaidTotal = BigDecimal.ZERO;
    public BigDecimal interestPaidTotal = BigDecimal.ZERO;
    public BigDecimal partPaymentTotal = BigDecimal.ZERO;
    public int paymentsPosted;
    public BigDecimal paymentsPostedAmount = BigDecimal.ZERO;
    public BigDecimal modelOutstanding;          // principal outstanding per test ledger, after today's payments
    public BigDecimal openingOutstanding;        // principal outstanding at start of the business day
    public BigDecimal cumulativeAccrued = BigDecimal.ZERO;
    public BigDecimal cumulativeExpectedAccrual = BigDecimal.ZERO;

    // lifecycle flags
    public boolean setupFailed;
    public boolean closed;
    public LocalDate closedOn;
    public boolean diverged;                     // system accepted something the contract says it must reject; stop modelling
    public boolean shortTried;
    public boolean partDone;
    public boolean forecloseDone;
    public boolean restructureProbed;
    public boolean replayChecked;
    public LocalDate endDate;                    // last simulated day for this account
    public LocalDate cureDate;                   // MISS_THEN_CURE: the day arrears are cleared
    public String lastStatus = "PENDING_DISBURSEMENT";
    public final List<String> statusHistory = new ArrayList<>();
    public int maxDpdSeen;
    public int dpdAtEod;
    public int lastAccrualRows;
    public final Map<String, String> notes = new LinkedHashMap<>();

    public AccountSim(int index, Scenario scenario, long customerId, long sanctionRefId) {
        this.index = index;
        this.scenario = scenario;
        this.customerId = customerId;
        this.sanctionRefId = sanctionRefId;
    }

    public boolean active() {
        return !setupFailed && accountId != null && !closed;
    }

    public ScheduleInvariants.Row row(int no) {
        for (ScheduleInvariants.Row r : rows) {
            if (r.no() == no) {
                return r;
            }
        }
        return null;
    }

    public List<ScheduleInvariants.Row> unpaidRows() {
        List<ScheduleInvariants.Row> out = new ArrayList<>();
        for (ScheduleInvariants.Row r : rows) {
            if (!paid.contains(r.no())) {
                out.add(r);
            }
        }
        return out;
    }

    public List<LocalDate> unpaidDueDates() {
        return unpaidRows().stream().map(ScheduleInvariants.Row::due).toList();
    }

    public String label() {
        return scenario.scenarioId() + "#" + index;
    }
}
