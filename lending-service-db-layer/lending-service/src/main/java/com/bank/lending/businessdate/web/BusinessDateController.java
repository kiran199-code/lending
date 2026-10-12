package com.bank.lending.businessdate.web;

import com.bank.lending.businessdate.dto.*;
import com.bank.lending.businessdate.service.BusinessDateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/business-date")
@RequiredArgsConstructor
@Tag(name = "Business Date", description = "The platform's business date, end-of-day transitions and the working-day calendar")
public class BusinessDateController {

    private static final String ACTOR_HEADER = "X-Actor";

    private final BusinessDateService businessDateService;

    @Operation(summary = "Current business date, its status (OPEN / EOD_RUNNING) and whether it is a working day")
    @GetMapping
    public BusinessDateResponse current() {
        return businessDateService.state();
    }

    @Operation(summary = "Last 30 business dates with when each opened, started EOD and closed")
    @GetMapping("/history")
    public List<BusinessDateHistoryResponse> history() {
        return businessDateService.history().stream().map(BusinessDateHistoryResponse::from).toList();
    }

    @Operation(summary = "Start end of day for the current business date (OPEN -> EOD_RUNNING); 409 if already running")
    @PostMapping("/eod/start")
    public BusinessDateResponse startEndOfDay(@RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return businessDateService.startEndOfDay(actor);
    }

    @Operation(summary = "Close the business date and open the next day (EOD_RUNNING -> OPEN); idempotent on expectedBusinessDate")
    @PostMapping("/advance")
    public BusinessDateResponse advance(@Valid @RequestBody AdvanceBusinessDateRequest request,
                                        @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return businessDateService.advance(request.expectedBusinessDate(), actor);
    }

    @Operation(summary = "Initialise or move the business date (only while OPEN; forward only unless business-date.allow-backward-set=true)")
    @PutMapping
    public BusinessDateResponse set(@Valid @RequestBody SetBusinessDateRequest request,
                                    @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return businessDateService.set(request.date(), request.reason(), actor == null ? "api" : actor);
    }

    @Operation(summary = "Is the given date a working day (not a weekend or listed holiday)?")
    @GetMapping("/working-day")
    public java.util.Map<String, Object> workingDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return java.util.Map.of("date", date.toString(), "workingDay", businessDateService.isWorkingDay(date));
    }

    @Operation(summary = "Bank holidays between two dates")
    @GetMapping("/holidays")
    public List<HolidayResponse> holidays(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return businessDateService.holidays(from, to).stream().map(HolidayResponse::from).toList();
    }

    @Operation(summary = "Add or rename a bank holiday")
    @PutMapping("/holidays/{date}")
    public HolidayResponse saveHoliday(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @Valid @RequestBody HolidayRequest request) {
        return HolidayResponse.from(businessDateService.saveHoliday(date, request.description()));
    }

    @Operation(summary = "Remove a bank holiday")
    @DeleteMapping("/holidays/{date}")
    public ResponseEntity<Void> deleteHoliday(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        businessDateService.deleteHoliday(date);
        return ResponseEntity.noContent().build();
    }
}
