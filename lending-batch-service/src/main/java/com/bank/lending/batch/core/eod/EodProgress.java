package com.bank.lending.batch.core.eod;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Live view of the current (or most recent) end-of-day run, kept in memory by this instance and fed by
 * {@link EodListener} callbacks. {@link #snapshot()} returns an immutable copy that is safe to serialise
 * while the run is still going.
 *
 * <p>States: IDLE (nothing has run since this instance started), RUNNING, ADVANCED (finished, date moved
 * on) and FAILED (stopped; the date stays in EOD_RUNNING and a re-run resumes).
 */
public class EodProgress implements EodListener {

    public enum State { IDLE, RUNNING, ADVANCED, FAILED }

    /** Job row. status: PENDING, RUNNING, COMPLETED, SKIPPED (already done), FAILED (or another non-COMPLETED Spring Batch status). */
    public record JobRow(String jobName, String status, Instant startedAt, Instant finishedAt, String message) { }

    public record View(State state, String actor, String businessDate, boolean resumed, Instant startedAt, Instant finishedAt,
                       String currentJob, int total, int done, List<JobRow> jobs, String newBusinessDate, String message) { }

    private State state = State.IDLE;
    private String actor;
    private String businessDate;
    private boolean resumed;
    private Instant startedAt;
    private Instant finishedAt;
    private String currentJob;
    private String newBusinessDate;
    private String message;
    private final List<MutableRow> rows = new ArrayList<>();

    private static final class MutableRow {
        final String jobName;
        String status = "PENDING";
        Instant startedAt;
        Instant finishedAt;
        String message;

        MutableRow(String jobName) {
            this.jobName = jobName;
        }
    }

    /** A run was requested: clears the previous run and shows RUNNING while lending-service is contacted. */
    public synchronized void begin(String actor) {
        this.state = State.RUNNING;
        this.actor = actor;
        this.businessDate = null;
        this.resumed = false;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.currentJob = null;
        this.newBusinessDate = null;
        this.message = "Contacting lending-service";
        this.rows.clear();
    }

    @Override
    public synchronized void started(LocalDate date, List<String> jobNames, boolean wasResumed) {
        this.state = State.RUNNING; // also covers a run that did not go through begin()
        if (this.startedAt == null || this.finishedAt != null) {
            this.startedAt = Instant.now();
            this.finishedAt = null;
        }
        this.businessDate = date.toString();
        this.resumed = wasResumed;
        this.message = wasResumed ? "Resuming end of day for " + date : "End of day started for " + date;
        this.rows.clear();
        jobNames.forEach(n -> rows.add(new MutableRow(n)));
    }

    @Override
    public synchronized void jobStarted(String jobName) {
        MutableRow r = row(jobName);
        r.status = "RUNNING";
        r.startedAt = Instant.now();
        this.currentJob = jobName;
        this.message = "Running " + jobName;
    }

    @Override
    public synchronized void jobFinished(EodJobResult result) {
        MutableRow r = row(result.jobName());
        r.status = result.status();
        r.message = result.message();
        r.finishedAt = Instant.now();
        if (r.startedAt == null) {
            r.startedAt = r.finishedAt; // skipped jobs never "start"
        }
        if (result.jobName().equals(currentJob)) {
            currentJob = null;
        }
    }

    @Override
    public synchronized void finished(EodRunSummary summary) {
        this.state = summary.outcome() == EodRunSummary.Outcome.ADVANCED ? State.ADVANCED : State.FAILED;
        this.finishedAt = summary.finishedAt() != null ? summary.finishedAt() : Instant.now();
        this.currentJob = null;
        if (summary.businessDate() != null) {
            this.businessDate = summary.businessDate();
        }
        this.newBusinessDate = summary.newBusinessDate();
        this.message = state == State.ADVANCED
                ? "End of day complete. Business date is now " + summary.newBusinessDate()
                : summary.error();
    }

    /** The run thread died unexpectedly. */
    public synchronized void crashed(String error) {
        this.state = State.FAILED;
        this.finishedAt = Instant.now();
        this.currentJob = null;
        this.message = error == null ? "End of day crashed" : error;
    }

    public synchronized View snapshot() {
        List<JobRow> jobs = new ArrayList<>(rows.size());
        int done = 0;
        for (MutableRow r : rows) {
            jobs.add(new JobRow(r.jobName, r.status, r.startedAt, r.finishedAt, r.message));
            if ("COMPLETED".equals(r.status) || "SKIPPED".equals(r.status)) {
                done++;
            }
        }
        return new View(state, actor, businessDate, resumed, startedAt, finishedAt, currentJob, rows.size(), done,
                List.copyOf(jobs), newBusinessDate, message);
    }

    private MutableRow row(String jobName) {
        for (MutableRow r : rows) {
            if (r.jobName.equals(jobName)) {
                return r;
            }
        }
        MutableRow added = new MutableRow(jobName); // a job that was not in the planned list; keep it visible
        rows.add(added);
        return added;
    }
}
