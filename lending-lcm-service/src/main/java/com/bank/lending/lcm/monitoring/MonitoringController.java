package com.bank.lending.lcm.monitoring;

import com.bank.lending.lcm.common.Actor;
import com.bank.lending.lcm.monitoring.MonitoringDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/lcm/monitoring")
@Tag(name = "Monitoring", description = "Sweep, breaches and ledger integrity")
public class MonitoringController {

    private final MonitoringService service;

    public MonitoringController(MonitoringService service) {
        this.service = service;
    }

    @PostMapping("/sweep")
    @Operation(summary = "Run the sweep now: expire reservations and facilities, open and close breaches (lcm.monitoring.run)")
    public SweepResult sweep(@RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.sweep(asOf);
    }

    @GetMapping("/breaches")
    @Operation(summary = "List breaches, e.g. status=OPEN (lcm.monitoring.read)")
    public List<BreachView> breaches(@RequestParam(value = "status", required = false) String status) {
        return service.breaches(status);
    }

    @PostMapping("/breaches/{id}/acknowledge")
    @Operation(summary = "Acknowledge an open breach (lcm.breach.acknowledge)")
    public BreachView acknowledge(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.acknowledge(id, Actor.required(actor));
    }

    @GetMapping("/integrity")
    @Operation(summary = "Check that every facility counter equals the sum of its ledger rows (lcm.monitoring.read)")
    public IntegrityResult integrity() {
        return service.integrity();
    }
}
