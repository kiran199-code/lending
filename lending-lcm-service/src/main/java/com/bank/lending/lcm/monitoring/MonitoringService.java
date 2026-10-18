package com.bank.lending.lcm.monitoring;

import com.bank.lending.lcm.collateral.CollateralModels.Collateral;
import com.bank.lending.lcm.collateral.CollateralModels.TypeConfig;
import com.bank.lending.lcm.collateral.CollateralModels.Valuation;
import com.bank.lending.lcm.collateral.CollateralRepository;
import com.bank.lending.lcm.collateral.ValuationCalculator;
import com.bank.lending.lcm.common.Audit;
import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.common.Money;
import com.bank.lending.lcm.facility.AvailabilityCalculator;
import com.bank.lending.lcm.facility.AvailabilityCalculator.Level;
import com.bank.lending.lcm.facility.Facility;
import com.bank.lending.lcm.facility.FacilityRepository;
import com.bank.lending.lcm.facility.FacilityService;
import com.bank.lending.lcm.facility.FacilityStatus;
import com.bank.lending.lcm.monitoring.MonitoringDtos.*;
import com.bank.lending.lcm.monitoring.MonitoringRepository.Live;
import com.bank.lending.lcm.utilisation.UtilisationRepository;
import com.bank.lending.lcm.utilisation.UtilisationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Housekeeping and monitoring. Each unit of work runs in its own short transaction so one bad row cannot
 * block the rest and reservation locks are held briefly. Safe to run on several instances at once: reservation
 * expiry re-checks under a row lock and breaches are protected by partial unique indexes.
 */
@Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final MonitoringRepository breaches;
    private final UtilisationRepository utilisationRepo;
    private final UtilisationService utilisation;
    private final FacilityRepository facilities;
    private final FacilityService facilityService;
    private final CollateralRepository collateral;
    private final Audit audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MonitoringService(MonitoringRepository breaches, UtilisationRepository utilisationRepo, UtilisationService utilisation,
                             FacilityRepository facilities, FacilityService facilityService, CollateralRepository collateral,
                             Audit audit, Clock clock, PlatformTransactionManager txManager) {
        this.breaches = breaches;
        this.utilisationRepo = utilisationRepo;
        this.utilisation = utilisation;
        this.facilities = facilities;
        this.facilityService = facilityService;
        this.collateral = collateral;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "${lcm.monitoring.sweep-cron:0 * * * * *}")
    public void scheduledSweep() {
        try {
            SweepResult r = sweep(null);
            if (r.reservationsExpired() + r.facilitiesExpired() + r.breachesOpened() + r.breachesClosed() > 0) {
                log.info("LCM sweep {}: {} reservations expired, {} facilities expired, {} breaches opened, {} closed",
                        r.asOf(), r.reservationsExpired(), r.facilitiesExpired(), r.breachesOpened(), r.breachesClosed());
            }
        } catch (RuntimeException e) {
            log.error("LCM sweep failed", e);
        }
    }

    public SweepResult sweep(LocalDate asOfOrNull) {
        LocalDate asOf = asOfOrNull != null ? asOfOrNull : LocalDate.now(clock);
        int reservations = expireReservations();
        int expired = expireFacilities(asOf);
        int[] counts = evaluateBreaches(asOf);
        return new SweepResult(asOf, reservations, expired, counts[0], counts[1]);
    }

    // ---------------------------------------------------------------- steps

    private int expireReservations() {
        int total = 0;
        for (int batch = 0; batch < 20; batch++) {
            List<Long> due = utilisationRepo.expiredReservationIds(200);
            if (due.isEmpty()) break;
            int done = 0;
            for (Long id : due) {
                try {
                    if (utilisation.expire(id)) done++;
                } catch (RuntimeException e) {
                    log.warn("Could not expire reservation {}: {}", id, e.getMessage());
                }
            }
            total += done;
            if (done == 0) break;
        }
        return total;
    }

    private int expireFacilities(LocalDate asOf) {
        int n = 0;
        for (Facility f : facilities.expiredButOpen(asOf)) {
            Boolean changed = tx.execute(status -> {
                Facility locked = facilities.lockAll(List.of(f.id())).get(0);
                if (locked.status() != FacilityStatus.ACTIVE && locked.status() != FacilityStatus.SUSPENDED && locked.status() != FacilityStatus.FROZEN) {
                    return false;
                }
                facilities.setStatus(locked.id(), FacilityStatus.EXPIRED, null);
                audit.event("FACILITY_STATUS_CHANGED", "FACILITY", locked.facilityRef(),
                        Json.obj("facilityRef", locked.facilityRef(), "from", locked.status().name(), "to", "EXPIRED"));
                audit.log("FACILITY", locked.facilityRef(), "EXPIRED", "system", "expiry " + locked.expiryDate());
                return true;
            });
            if (Boolean.TRUE.equals(changed)) n++;
        }
        return n;
    }

    private int[] evaluateBreaches(LocalDate asOf) {
        int[] counts = new int[2];
        for (Facility f : facilities.liveFacilities()) {
            try {
                tx.execute(status -> {
                    evaluateFacility(f.id(), asOf, counts);
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Breach evaluation failed for facility {}: {}", f.facilityRef(), e.getMessage());
            }
        }
        for (Collateral c : collateral.activeOrSuspended()) {
            try {
                tx.execute(status -> {
                    evaluateCollateral(c, asOf, counts);
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Breach evaluation failed for collateral {}: {}", c.collateralRef(), e.getMessage());
            }
        }
        return counts;
    }

    private void evaluateFacility(long facilityId, LocalDate asOf, int[] counts) {
        Level level = facilityService.chainLevels(facilityId, asOf, false).get(0);
        Facility f = level.facility();
        BigDecimal used = AvailabilityCalculator.consumed(f).add(f.reserved());
        BigDecimal base = AvailabilityCalculator.baseLimit(level);

        boolean over = used.compareTo(base) > 0;
        BigDecimal overBy = used.subtract(base);
        String sev = over && overBy.compareTo(base.multiply(new BigDecimal("0.10"))) > 0 ? "HIGH" : "MEDIUM";
        track(f.id(), null, "OVER_LIMIT", over, sev, "Utilised plus reserved " + used + " exceeds limit " + base + " on " + f.facilityRef(), counts);

        boolean dpShort = false;
        BigDecimal dp = level.drawingPower();
        if (f.dpGoverned() && dp != null) dpShort = used.compareTo(dp) > 0 && dp.compareTo(base) < 0;
        track(f.id(), null, "DRAWING_POWER_SHORTFALL", dpShort, "HIGH",
                "Utilised plus reserved " + used + " exceeds drawing power " + dp + " on " + f.facilityRef(), counts);

        boolean expiredOut = f.status() == FacilityStatus.EXPIRED && f.utilised().signum() > 0;
        track(f.id(), null, "EXPIRED_WITH_UTILISATION", expiredOut, "LOW",
                "Facility " + f.facilityRef() + " expired on " + f.expiryDate() + " with " + f.utilised() + " still outstanding", counts);
    }

    private void evaluateCollateral(Collateral c, LocalDate asOf, int[] counts) {
        TypeConfig t = collateral.type(c.collateralType()).orElseThrow();
        Valuation latest = collateral.latestApproved(c.id()).orElse(null);
        boolean stale = latest == null || !ValuationCalculator.valuationCurrent(latest.valuationDate(), t.validityDays(), asOf);
        track(null, c.id(), "VALUATION_STALE", stale, "MEDIUM", latest == null ? "No approved valuation for " + c.collateralRef()
                : "Valuation of " + c.collateralRef() + " dated " + latest.valuationDate() + " is older than " + t.validityDays() + " days", counts);
        boolean insured = ValuationCalculator.insuranceValid(t.requiresInsurance(), c.insuranceExpiry(), asOf);
        track(null, c.id(), "INSURANCE_EXPIRED", !insured, "MEDIUM", "Insurance on " + c.collateralRef() + " is missing or expired ("
                + c.insuranceExpiry() + ")", counts);
    }

    /** Opens the breach when the condition holds and none is live; closes the live one when it no longer holds. */
    private void track(Long facilityId, Long collateralId, String type, boolean holds, String severity, String detail, int[] counts) {
        Live live = facilityId != null ? breaches.liveFacilityBreach(facilityId, type).orElse(null)
                : breaches.liveCollateralBreach(collateralId, type).orElse(null);
        if (holds && live == null) {
            try {
                breaches.open(type, severity, facilityId, collateralId, detail);
            } catch (DuplicateKeyException e) {
                return; // another instance opened it first
            }
            counts[0]++;
            audit.event("BREACH_OPENED", facilityId != null ? "FACILITY" : "COLLATERAL", String.valueOf(facilityId != null ? facilityId : collateralId),
                    Json.obj("breachType", type, "severity", severity, "detail", detail));
        } else if (!holds && live != null) {
            breaches.close(live.id());
            counts[1]++;
            audit.event("BREACH_CLOSED", facilityId != null ? "FACILITY" : "COLLATERAL", String.valueOf(facilityId != null ? facilityId : collateralId),
                    Json.obj("breachType", type));
        }
    }

    // ---------------------------------------------------------------- reads / acknowledge

    public List<BreachView> breaches(String status) {
        return tx.execute(s -> breaches.list(status));
    }

    public BreachView acknowledge(long id, String actor) {
        return tx.execute(s -> {
            if (breaches.acknowledge(id, actor) == 0) {
                BreachView b = breaches.find(id).orElseThrow(() -> LcmException.notFound("Breach", String.valueOf(id)));
                throw LcmException.conflict("NOT_OPEN", "Breach " + id + " is " + b.status());
            }
            audit.log("BREACH", String.valueOf(id), "ACKNOWLEDGED", actor, null);
            return breaches.find(id).orElseThrow();
        });
    }

    public IntegrityResult integrity() {
        List<String> diffs = tx.execute(s -> utilisationRepo.integrityDifferences());
        return new IntegrityResult(diffs.isEmpty(), diffs);
    }
}
