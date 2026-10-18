package com.bank.lending.lcm.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class MonitoringDtos {
    private MonitoringDtos() {}

    public record BreachView(long id, String breachType, String severity, String facilityRef, String collateralRef, String detail,
                             String status, String acknowledgedBy, OffsetDateTime detectedAt, OffsetDateTime closedAt) {}

    public record SweepResult(LocalDate asOf, int reservationsExpired, int facilitiesExpired, int breachesOpened, int breachesClosed) {}

    public record IntegrityResult(boolean ok, List<String> differences) {}
}
