package com.bank.lending.batch.maintenance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Same body shape as the rest of the admin API ({@code timestamp, status, error, message}) plus the {@code code}. */
@RestControllerAdvice(assignableTypes = MaintenanceController.class)
public class MaintenanceExceptionHandler {

    @ExceptionHandler(MaintenanceException.class)
    public ResponseEntity<Map<String, Object>> refused(MaintenanceException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", e.status().value());
        body.put("error", e.status().getReasonPhrase());
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }
}
