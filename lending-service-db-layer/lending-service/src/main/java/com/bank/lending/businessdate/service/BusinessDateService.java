package com.bank.lending.businessdate.service;

import com.bank.lending.businessdate.domain.BusinessDateHistory;
import com.bank.lending.businessdate.domain.BusinessDateState;
import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.businessdate.domain.NonWorkingDay;
import com.bank.lending.businessdate.dto.BusinessDateResponse;
import com.bank.lending.businessdate.repository.BusinessDateHistoryRepository;
import com.bank.lending.businessdate.repository.BusinessDateStateRepository;
import com.bank.lending.businessdate.repository.NonWorkingDayRepository;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.common.partition.PartitionMaintenanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Owner of the platform's business date. Everything that needs "today" for business purposes —
 * the EOD batch, value-date checks on payments, accrual, delinquency, statements — asks this
 * service instead of reading the system clock. The wall clock is only used to stamp audit columns
 * and to report {@code systemDate} for comparison.
 *
 * <p>State changes take a row lock on the single state row, so concurrent callers (or several
 * batch instances) are serialised and a double advance is impossible.
 */
@Slf4j
@Service
public class BusinessDateService {

    private final BusinessDateStateRepository stateRepository;
    private final BusinessDateHistoryRepository historyRepository;
    private final NonWorkingDayRepository holidayRepository;
    private final PartitionMaintenanceService partitionMaintenanceService;

    private final Set<DayOfWeek> weekend;
    private final boolean enforceNoFutureDates;
    private final boolean allowBackwardSet;
    private final ZoneId systemZone;

    public BusinessDateService(BusinessDateStateRepository stateRepository,
                               BusinessDateHistoryRepository historyRepository,
                               NonWorkingDayRepository holidayRepository,
                               PartitionMaintenanceService partitionMaintenanceService,
                               @Value("${business-date.weekend-days:SATURDAY,SUNDAY}") String weekendDays,
                               @Value("${business-date.enforce-no-future-dates:true}") boolean enforceNoFutureDates,
                               @Value("${business-date.allow-backward-set:false}") boolean allowBackwardSet,
                               @Value("${business-date.system-timezone:Asia/Kolkata}") String systemTimezone) {
        this.stateRepository = stateRepository;
        this.historyRepository = historyRepository;
        this.holidayRepository = holidayRepository;
        this.partitionMaintenanceService = partitionMaintenanceService;
        this.weekend = parseWeekend(weekendDays);
        this.enforceNoFutureDates = enforceNoFutureDates;
        this.allowBackwardSet = allowBackwardSet;
        this.systemZone = ZoneId.of(systemTimezone);
    }

    static Set<DayOfWeek> parseWeekend(String csv) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> DayOfWeek.valueOf(s.toUpperCase())).forEach(days::add);
        }
        return days;
    }

    // ---- reads ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LocalDate currentDate() {
        return load().getBusinessDate();
    }

    @Transactional(readOnly = true)
    public BusinessDateResponse state() {
        return toResponse(load());
    }

    @Transactional(readOnly = true)
    public List<BusinessDateHistory> history() {
        return historyRepository.findTop30ByOrderByBusinessDateDesc();
    }

    /**
     * Rejects a transaction date after the current business date. Switched off with
     * {@code business-date.enforce-no-future-dates=false} (not recommended outside local work).
     */
    @Transactional(readOnly = true)
    public void assertNotFuture(LocalDate date, String what) {
        if (!enforceNoFutureDates) {
            return;
        }
        BusinessDateRules.checkNotAfter(date, currentDate(), what);
    }

    @Transactional(readOnly = true)
    public boolean isWorkingDay(LocalDate date) {
        return BusinessDateRules.isWorkingDay(date, weekend,
                holidayRepository.existsById(date) ? Set.of(date) : Set.of());
    }

    // ---- transitions ---------------------------------------------------------------------

    /** OPEN -> EOD_RUNNING. 409 if EOD is already running. Returns the date being closed. */
    @Transactional
    public BusinessDateResponse startEndOfDay(String actor) {
        BusinessDateState s = lock();
        BusinessDateRules.checkStartEndOfDay(s.getStatus(), s.getBusinessDate());
        s.setStatus(BusinessDateStatus.EOD_RUNNING);
        touch(s, actor);
        stateRepository.save(s);
        historyRepository.findById(s.getBusinessDate()).ifPresent(h -> {
            h.setEodStartedAt(OffsetDateTime.now());
            historyRepository.save(h);
        });
        log.info("End of day started for business date {} by {}", s.getBusinessDate(), actor);
        return toResponse(s);
    }

    /**
     * EOD_RUNNING -> OPEN on the next day. Idempotent on {@code expected}: a retry after the date has
     * already moved returns the current state without moving again.
     */
    @Transactional
    public BusinessDateResponse advance(LocalDate expected, String actor) {
        BusinessDateState s = lock();
        if (BusinessDateRules.checkAdvance(s.getStatus(), s.getBusinessDate(), expected)
                == BusinessDateRules.AdvanceDecision.ALREADY_ADVANCED) {
            return toResponse(s);
        }
        LocalDate closing = s.getBusinessDate();
        LocalDate next = BusinessDateRules.nextDate(closing);

        historyRepository.findById(closing).ifPresent(h -> {
            h.setClosedAt(OffsetDateTime.now());
            h.setClosedBy(actor);
            historyRepository.save(h);
        });
        historyRepository.save(BusinessDateHistory.builder().businessDate(next).openedAt(OffsetDateTime.now()).build());

        s.setPreviousDate(closing);
        s.setBusinessDate(next);
        s.setStatus(BusinessDateStatus.OPEN);
        touch(s, actor);
        stateRepository.save(s);

        // Accruals and payments are partitioned by month; make sure the new date has a partition.
        partitionMaintenanceService.ensurePartitions(next);
        log.info("Business date advanced {} -> {} by {}", closing, next, actor);
        return toResponse(s);
    }

    /**
     * Initialise or move the date for testing: only while OPEN, and forward only unless
     * {@code business-date.allow-backward-set=true}. Recorded in the history with the reason.
     */
    @Transactional
    public BusinessDateResponse set(LocalDate target, String reason, String actor) {
        BusinessDateState s = lock();
        BusinessDateRules.checkSet(s.getStatus(), s.getBusinessDate(), target, allowBackwardSet);
        if (target.equals(s.getBusinessDate())) {
            return toResponse(s);
        }
        LocalDate before = s.getBusinessDate();
        s.setPreviousDate(before);
        s.setBusinessDate(target);
        touch(s, actor);
        stateRepository.save(s);

        BusinessDateHistory h = historyRepository.findById(target).orElseGet(() -> BusinessDateHistory.builder()
                .businessDate(target).openedAt(OffsetDateTime.now()).build());
        String note = "Set from " + before + " by " + actor + (reason != null ? ": " + reason : "");
        h.setNote(note.length() > 200 ? note.substring(0, 200) : note);
        historyRepository.save(h);

        partitionMaintenanceService.ensurePartitions(target);
        log.warn("Business date SET {} -> {} by {} ({})", before, target, actor, reason);
        return toResponse(s);
    }

    /**
     * Start-from-scratch only (called by the maintenance clear, in the same transaction): puts the business date
     * on today's date in {@code business-date.system-timezone}, status OPEN, whatever it was before, and restarts the
     * history with that one date. Unlike {@link #set} it may move the date backwards, because the accruals and
     * payments that a backward move would leave behind were deleted together with the rest of the data.
     * Holidays are kept.
     */
    @Transactional
    public BusinessDateResponse resetToToday(String actor) {
        BusinessDateState s = lock();
        LocalDate today = LocalDate.now(systemZone);
        LocalDate before = s.getBusinessDate();
        s.setBusinessDate(today);
        s.setPreviousDate(null);
        s.setStatus(BusinessDateStatus.OPEN);
        touch(s, actor);
        stateRepository.save(s);

        // A bulk delete runs at once, so a history row for today that already exists cannot clash with the new one.
        historyRepository.deleteAllInBatch();
        String note = "Reset to the current date by " + (actor == null || actor.isBlank() ? "system" : actor)
                + " (was " + before + ")";
        historyRepository.save(BusinessDateHistory.builder().businessDate(today).openedAt(OffsetDateTime.now())
                .note(note.length() > 200 ? note.substring(0, 200) : note).build());

        partitionMaintenanceService.ensurePartitions(today);
        log.warn("Business date RESET {} -> {} by {}", before, today, actor);
        return toResponse(s);
    }

    // ---- calendar ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NonWorkingDay> holidays(LocalDate from, LocalDate to) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDate(from, to);
    }

    @Transactional
    public NonWorkingDay saveHoliday(LocalDate date, String description) {
        return holidayRepository.save(new NonWorkingDay(date, description));
    }

    @Transactional
    public void deleteHoliday(LocalDate date) {
        if (!holidayRepository.existsById(date)) {
            throw new ResourceNotFoundException("No holiday on " + date);
        }
        holidayRepository.deleteById(date);
    }

    // ---- internals -----------------------------------------------------------------------

    private BusinessDateState load() {
        return stateRepository.findById(BusinessDateState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("business_date_state row is missing (migration V13)"));
    }

    private BusinessDateState lock() {
        return stateRepository.findForUpdate()
                .orElseThrow(() -> new IllegalStateException("business_date_state row is missing (migration V13)"));
    }

    private void touch(BusinessDateState s, String actor) {
        s.setUpdatedBy(actor == null || actor.isBlank() ? "system" : actor);
        s.setUpdatedAt(OffsetDateTime.now());
    }

    private BusinessDateResponse toResponse(BusinessDateState s) {
        LocalDate d = s.getBusinessDate();
        Set<LocalDate> holidays = holidayRepository.existsById(d) ? Set.of(d) : Set.of();
        return new BusinessDateResponse(d, s.getPreviousDate(), BusinessDateRules.nextDate(d), s.getStatus(),
                BusinessDateRules.isWorkingDay(d, weekend, holidays), LocalDate.now(systemZone),
                s.getUpdatedBy(), s.getUpdatedAt());
    }
}
