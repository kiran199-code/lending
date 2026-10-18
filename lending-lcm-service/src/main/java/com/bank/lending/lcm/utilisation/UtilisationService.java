package com.bank.lending.lcm.utilisation;

import com.bank.lending.lcm.common.Audit;
import com.bank.lending.lcm.common.Hashing;
import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.common.Money;
import com.bank.lending.lcm.config.LcmSettings;
import com.bank.lending.lcm.facility.AvailabilityCalculator;
import com.bank.lending.lcm.facility.AvailabilityCalculator.Level;
import com.bank.lending.lcm.facility.AvailabilityCalculator.Rejection;
import com.bank.lending.lcm.facility.Facility;
import com.bank.lending.lcm.facility.FacilityRepository;
import com.bank.lending.lcm.facility.FacilityService;
import com.bank.lending.lcm.utilisation.UtilisationDtos.*;
import com.bank.lending.lcm.utilisation.UtilisationRepository.IdemOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Reserve / confirm / release / repay / close against a facility and all its ancestors.
 *
 * <p>Concurrency: every operation locks the whole facility chain in ascending id order (one statement,
 * {@code ORDER BY id FOR UPDATE}) before reading counters, so two drawdowns can never both see the same headroom
 * and chains never deadlock. Reservation rows are locked before facilities, positions after.
 */
@Service
public class UtilisationService {

    private final UtilisationRepository repo;
    private final FacilityRepository facilities;
    private final FacilityService facilityService;
    private final Audit audit;
    private final LcmSettings settings;
    private final Clock clock;

    public UtilisationService(UtilisationRepository repo, FacilityRepository facilities, FacilityService facilityService,
                              Audit audit, LcmSettings settings, Clock clock) {
        this.repo = repo;
        this.facilities = facilities;
        this.facilityService = facilityService;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- check (non-binding)

    @Transactional(readOnly = true)
    public CheckResponse check(CheckRequest r) {
        Facility leaf = facilities.getByRef(r.facilityRef());
        if (r.customerId() != null && r.customerId() != leaf.customerId()) {
            // A facility belongs to one customer; linking another customer's loan to it would draw down the wrong limit.
            return new CheckResponse(false, "CUSTOMER_MISMATCH", "Facility " + leaf.facilityRef()
                    + " belongs to customer " + leaf.customerId() + ", not customer " + r.customerId(), leaf.facilityRef(), null);
        }
        LocalDate asOf = r.businessDate() != null ? r.businessDate() : LocalDate.now(clock);
        List<Level> chain = facilityService.chainLevels(leaf.id(), asOf, false);
        Rejection rej = AvailabilityCalculator.firstRejection(chain, Money.scale(r.amount()), r.currency(), r.branchCode(), asOf);
        BigDecimal avail = AvailabilityCalculator.effectiveAvailable(chain);
        return rej == null ? new CheckResponse(true, null, null, null, avail)
                : new CheckResponse(false, rej.code(), rej.message(), rej.facilityRef(), avail);
    }

    // ---------------------------------------------------------------- reserve

    @Transactional
    public ReservationView reserve(ReserveRequest r, String idemKey, String actor) {
        BigDecimal amount = Money.scale(r.amount());
        String hash = Hashing.sha256(String.join("|", "RESERVE", r.facilityRef(), amount.toPlainString(), r.currency().toUpperCase(),
                String.valueOf(r.branchCode()), r.referenceType(), r.referenceId(), String.valueOf(r.ttlMinutes()), String.valueOf(r.enforce())));
        IdemOutcome idem = repo.claim(idemKey, "RESERVE", hash);
        if (idem.replay()) return reservationView(repo.findByRef(idem.resultRef()).orElseThrow()).asReplay();

        Facility leaf = facilities.getByRef(r.facilityRef());
        LocalDate asOf = r.businessDate() != null ? r.businessDate() : LocalDate.now(clock);
        List<Level> chain = facilityService.chainLevels(leaf.id(), asOf, true);

        if (repo.liveForReference(r.referenceType(), r.referenceId()).isPresent()) {
            throw LcmException.conflict("RESERVATION_EXISTS", "A live reservation already exists for " + r.referenceType() + " " + r.referenceId());
        }
        boolean enforce = settings.enforce() && !Boolean.FALSE.equals(r.enforce());
        Rejection rej = AvailabilityCalculator.firstRejection(chain, amount, r.currency(), r.branchCode(), asOf);
        if (rej != null && enforce) {
            throw LcmException.unprocessable(rej.code(), rej.message());
        }
        int ttl = r.ttlMinutes() == null ? settings.defaultTtlMinutes() : Math.max(1, Math.min(r.ttlMinutes(), settings.maxTtlMinutes()));
        long resId = repo.insertReservation(leaf.id(), amount, r.currency().toUpperCase(), r.referenceType(), r.referenceId(),
                enforce, rej == null ? null : rej.code(), ttl, actor);
        apply(chain, leaf, "RESERVE", resId, null, amount, amount, Money.ZERO, Money.ZERO, r.referenceType(), r.referenceId(), actor);
        Reservation res = repo.getReservation(resId);
        audit.event("RESERVATION_CREATED", "RESERVATION", res.reservationRef(), Json.obj("reservationRef", res.reservationRef(),
                "facilityRef", leaf.facilityRef(), "amount", amount, "referenceType", r.referenceType(), "referenceId", r.referenceId(),
                "wouldReject", rej == null ? null : rej.code()));
        repo.setResult(idemKey, res.reservationRef());
        return reservationView(res);
    }

    // ---------------------------------------------------------------- confirm

    @Transactional
    public ReservationView confirm(String reservationRef, BigDecimal requested, String idemKey, String actor) {
        String hash = Hashing.sha256("CONFIRM|" + reservationRef + "|" + (requested == null ? "-" : Money.scale(requested).toPlainString()));
        IdemOutcome idem = repo.claim(idemKey, "CONFIRM", hash);
        if (idem.replay()) return reservationView(repo.findByRef(idem.resultRef()).orElseThrow()).asReplay();

        Reservation res = repo.lockByRef(reservationRef).orElseThrow(() -> LcmException.notFound("Reservation", reservationRef));
        BigDecimal actual = requested == null ? res.amount() : Money.scale(requested);
        switch (res.status()) {
            case "CONFIRMED" -> {
                if (res.confirmedAmount().compareTo(actual) == 0) {
                    repo.setResult(idemKey, res.reservationRef());
                    return reservationView(res).asReplay();
                }
                throw LcmException.conflict("ALREADY_CONFIRMED", "Reservation was already confirmed for " + res.confirmedAmount());
            }
            case "RELEASED" -> throw LcmException.conflict("RESERVATION_RELEASED", "Reservation was released and cannot be confirmed");
            default -> { }
        }
        boolean late = res.status().equals("EXPIRED");
        if (!late && actual.compareTo(res.amount()) > 0) {
            throw LcmException.unprocessable("AMOUNT_EXCEEDS_RESERVATION", "Confirmed amount " + actual + " is more than reserved " + res.amount());
        }
        Facility leaf = facilities.getById(res.facilityId());
        List<Level> chain = facilityService.chainLevels(leaf.id(), LocalDate.now(clock), true);
        Position pos = repo.lockOrCreatePosition(leaf.id(), res.referenceType(), res.referenceId());
        if (pos.facilityId() != leaf.id()) {
            throw LcmException.conflict("POSITION_FACILITY_MISMATCH", res.referenceType() + " " + res.referenceId() + " is already drawn on another facility");
        }
        if (pos.status().equals("CLOSED")) {
            throw LcmException.conflict("POSITION_CLOSED", res.referenceType() + " " + res.referenceId() + " is closed");
        }
        // A late confirm (reservation already expired) releases nothing: the expiry already gave the reserved amount back.
        BigDecimal dRes = late ? Money.ZERO : res.amount().negate();
        apply(chain, leaf, "CONFIRM", res.id(), pos.id(), actual, dRes, actual, actual, res.referenceType(), res.referenceId(), actor);
        repo.updatePosition(pos.id(), pos.drawn().add(actual), pos.repaid(), pos.writtenOff(), "OPEN");
        repo.setReservationStatus(res.id(), "CONFIRMED", actual, late);
        audit.event("RESERVATION_CONFIRMED", "RESERVATION", res.reservationRef(), Json.obj("reservationRef", res.reservationRef(),
                "facilityRef", leaf.facilityRef(), "amount", actual, "late", late));
        if (late) {
            audit.log("RESERVATION", res.reservationRef(), "LATE_CONFIRM", actor, "applied after expiry, amount " + actual);
        }
        repo.setResult(idemKey, res.reservationRef());
        return reservationView(repo.getReservation(res.id()));
    }

    // ---------------------------------------------------------------- release / expire

    @Transactional
    public ReservationView release(String reservationRef, String idemKey, String actor) {
        String hash = Hashing.sha256("RELEASE|" + reservationRef);
        IdemOutcome idem = repo.claim(idemKey, "RELEASE", hash);
        if (idem.replay()) return reservationView(repo.findByRef(idem.resultRef()).orElseThrow()).asReplay();

        Reservation res = repo.lockByRef(reservationRef).orElseThrow(() -> LcmException.notFound("Reservation", reservationRef));
        switch (res.status()) {
            case "CONFIRMED" -> throw LcmException.conflict("ALREADY_CONFIRMED", "Reservation was confirmed; repay or close the position instead");
            case "RELEASED", "EXPIRED" -> {
                repo.setResult(idemKey, res.reservationRef());
                return reservationView(res).asReplay();
            }
            default -> { }
        }
        giveBack(res, "RELEASE", "RELEASED", actor);
        repo.setResult(idemKey, res.reservationRef());
        return reservationView(repo.getReservation(res.id()));
    }

    /** Expires one reservation whose TTL passed. Returns false when it was no longer due (confirmed or released meanwhile). */
    @Transactional
    public boolean expire(long reservationId) {
        Reservation res = repo.lockById(reservationId).orElse(null);
        if (res == null || !res.status().equals("RESERVED") || res.expiresAt().toInstant().isAfter(clock.instant())) {
            return false;
        }
        giveBack(res, "EXPIRE", "EXPIRED", "system");
        return true;
    }

    private void giveBack(Reservation res, String txnType, String newStatus, String actor) {
        Facility leaf = facilities.getById(res.facilityId());
        List<Level> chain = facilityService.chainLevels(leaf.id(), LocalDate.now(clock), true);
        apply(chain, leaf, txnType, res.id(), null, res.amount(), res.amount().negate(), Money.ZERO, Money.ZERO,
                res.referenceType(), res.referenceId(), actor);
        repo.setReservationStatus(res.id(), newStatus, null, false);
        audit.event(newStatus.equals("EXPIRED") ? "RESERVATION_EXPIRED" : "RESERVATION_RELEASED", "RESERVATION", res.reservationRef(),
                Json.obj("reservationRef", res.reservationRef(), "facilityRef", leaf.facilityRef(), "amount", res.amount()));
    }

    // ---------------------------------------------------------------- repay / close

    @Transactional
    public UtilisationResult repay(RepayRequest r, String idemKey, String actor) {
        BigDecimal amount = Money.scale(r.amount());
        String hash = Hashing.sha256(String.join("|", "REPAY", r.referenceType(), r.referenceId(), amount.toPlainString()));
        IdemOutcome idem = repo.claim(idemKey, "REPAY", hash);
        if (idem.replay()) return current("REPAY", r.referenceType(), r.referenceId(), amount, true);

        Position probe = repo.findPosition(r.referenceType(), r.referenceId())
                .orElseThrow(() -> LcmException.unprocessable("NO_POSITION", "No utilisation recorded for " + r.referenceType() + " " + r.referenceId()));
        Facility leaf = facilities.getById(probe.facilityId());
        List<Level> chain = facilityService.chainLevels(leaf.id(), LocalDate.now(clock), true);
        Position pos = repo.lockPosition(r.referenceType(), r.referenceId()).orElseThrow();
        if (pos.status().equals("CLOSED")) {
            throw LcmException.unprocessable("POSITION_CLOSED", r.referenceType() + " " + r.referenceId() + " is closed");
        }
        if (amount.compareTo(pos.outstanding()) > 0) {
            throw LcmException.unprocessable("REPAY_EXCEEDS_OUTSTANDING", "Repayment " + amount + " is more than outstanding " + pos.outstanding());
        }
        apply(chain, leaf, "REPAY", null, pos.id(), amount, Money.ZERO, amount.negate(), Money.ZERO, r.referenceType(), r.referenceId(), actor);
        BigDecimal repaid = pos.repaid().add(amount);
        boolean cleared = pos.drawn().subtract(repaid).subtract(pos.writtenOff()).signum() == 0;
        repo.updatePosition(pos.id(), pos.drawn(), repaid, pos.writtenOff(), cleared ? "CLOSED" : "OPEN");
        audit.event("UTILISATION_REPAID", "POSITION", r.referenceType() + ":" + r.referenceId(),
                Json.obj("facilityRef", leaf.facilityRef(), "referenceType", r.referenceType(), "referenceId", r.referenceId(), "amount", amount));
        repo.setResult(idemKey, r.referenceType() + ":" + r.referenceId());
        return current("REPAY", r.referenceType(), r.referenceId(), amount, false);
    }

    @Transactional
    public UtilisationResult close(CloseRequest r, String idemKey, String actor) {
        String hash = Hashing.sha256(String.join("|", "CLOSE", r.referenceType(), r.referenceId()));
        IdemOutcome idem = repo.claim(idemKey, "CLOSE", hash);
        if (idem.replay()) return current("CLOSE", r.referenceType(), r.referenceId(), Money.ZERO, true);

        Position probe = repo.findPosition(r.referenceType(), r.referenceId())
                .orElseThrow(() -> LcmException.unprocessable("NO_POSITION", "No utilisation recorded for " + r.referenceType() + " " + r.referenceId()));
        Facility leaf = facilities.getById(probe.facilityId());
        List<Level> chain = facilityService.chainLevels(leaf.id(), LocalDate.now(clock), true);
        Position pos = repo.lockPosition(r.referenceType(), r.referenceId()).orElseThrow();
        if (pos.status().equals("CLOSED")) {
            repo.setResult(idemKey, r.referenceType() + ":" + r.referenceId());
            return current("CLOSE", r.referenceType(), r.referenceId(), Money.ZERO, true);
        }
        BigDecimal outstanding = pos.outstanding();
        if (outstanding.signum() > 0) {
            apply(chain, leaf, "CLOSE", null, pos.id(), outstanding, Money.ZERO, outstanding.negate(), Money.ZERO,
                    r.referenceType(), r.referenceId(), actor);
        }
        repo.updatePosition(pos.id(), pos.drawn(), pos.repaid(), pos.writtenOff().add(outstanding), "CLOSED");
        audit.event("POSITION_CLOSED", "POSITION", r.referenceType() + ":" + r.referenceId(), Json.obj("facilityRef", leaf.facilityRef(),
                "referenceType", r.referenceType(), "referenceId", r.referenceId(), "released", outstanding, "reason", r.reason()));
        audit.log("POSITION", r.referenceType() + ":" + r.referenceId(), "CLOSED", actor, "released " + outstanding + (r.reason() == null ? "" : " - " + r.reason()));
        repo.setResult(idemKey, r.referenceType() + ":" + r.referenceId());
        return current("CLOSE", r.referenceType(), r.referenceId(), outstanding, false);
    }

    // ---------------------------------------------------------------- opening balances (bridge)

    /** Loads an existing loan's position without any limit check. Idempotent on the reference. */
    @Transactional
    public PositionView openPosition(String facilityRef, String refType, String refId, BigDecimal drawn, BigDecimal outstanding, String actor) {
        if (outstanding.signum() < 0 || outstanding.compareTo(drawn) > 0) {
            throw LcmException.badRequest("INVALID_POSITION", "outstanding must be between 0 and drawn");
        }
        var existing = repo.findPosition(refType, refId);
        if (existing.isPresent()) return positionView(existing.get());
        Facility leaf = facilities.getByRef(facilityRef);
        List<Level> chain = facilityService.chainLevels(leaf.id(), LocalDate.now(clock), true);
        BigDecimal d = Money.scale(drawn);
        BigDecimal o = Money.scale(outstanding);
        Position pos = repo.lockOrCreatePosition(leaf.id(), refType, refId);
        apply(chain, leaf, "OPENING", null, pos.id(), o, Money.ZERO, o, d, refType, refId, actor);
        repo.updatePosition(pos.id(), d, d.subtract(o), Money.ZERO, o.signum() == 0 ? "CLOSED" : "OPEN");
        audit.log("POSITION", refType + ":" + refId, "OPENING_BALANCE", actor, "drawn " + d + " outstanding " + o);
        return positionView(repo.findPosition(refType, refId).orElseThrow());
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ReservationView reservation(String ref) {
        return reservationView(repo.findByRef(ref).orElseThrow(() -> LcmException.notFound("Reservation", ref)));
    }

    @Transactional(readOnly = true)
    public PositionView position(String refType, String refId) {
        return positionView(repo.findPosition(refType, refId).orElseThrow(() -> LcmException.notFound("Position", refType + ":" + refId)));
    }

    // ---------------------------------------------------------------- helpers

    /** Applies the same deltas to every level of the chain and writes one ledger row per level. */
    private void apply(List<Level> chain, Facility origin, String type, Long reservationId, Long positionId, BigDecimal amount,
                       BigDecimal dRes, BigDecimal dUtil, BigDecimal dDrawn, String refType, String refId, String actor) {
        for (Level l : chain) {
            long id = l.facility().id();
            facilities.applyCounters(id, dRes, dUtil, dDrawn);
            repo.insertTxn(id, origin.id(), type, reservationId, positionId, amount, dRes, dUtil, dDrawn, refType, refId, actor);
        }
    }

    private UtilisationResult current(String op, String refType, String refId, BigDecimal amount, boolean replayed) {
        Position pos = repo.findPosition(refType, refId).orElseThrow();
        Facility f = facilities.getById(pos.facilityId());
        AvailabilityCalculator.Level leaf = facilityService.chainLevels(f.id(), LocalDate.now(clock), false).get(0);
        return new UtilisationResult(op, f.facilityRef(), refType, refId, amount, pos.outstanding(), f.utilised(),
                AvailabilityCalculator.available(leaf), replayed);
    }

    private ReservationView reservationView(Reservation r) {
        return new ReservationView(r.reservationRef(), repo.facilityRef(r.facilityId()), r.amount(), r.confirmedAmount(), r.currencyCode(),
                r.referenceType(), r.referenceId(), r.status(), r.enforced(), r.wouldReject(), r.lateConfirm(), r.expiresAt(), r.createdAt(), false);
    }

    private PositionView positionView(Position p) {
        return new PositionView(repo.facilityRef(p.facilityId()), p.referenceType(), p.referenceId(), p.drawn(), p.repaid(), p.writtenOff(),
                p.outstanding(), p.status());
    }
}
