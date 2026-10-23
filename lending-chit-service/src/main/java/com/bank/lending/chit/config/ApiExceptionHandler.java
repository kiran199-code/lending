package com.bank.lending.chit.config;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.ConfigInvalidException;
import com.bank.lending.chit.common.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every error leaves the service as {@code {timestamp, status, code, message}}; rule violations add a {@code violations} list. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ConfigInvalidException.class)
    public ResponseEntity<Map<String, Object>> configInvalid(ConfigInvalidException e) {
        Map<String, Object> m = body(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
        List<Map<String, Object>> violations = new ArrayList<>();
        for (RuleViolation v : e.violations()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("code", v.code());
            one.put("field", v.field());
            one.put("message", v.message());
            violations.add(one);
        }
        m.put("violations", violations);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(m);
    }

    @ExceptionHandler(ChitException.class)
    public ResponseEntity<Map<String, Object>> chit(ChitException e) {
        HttpStatus status = HttpStatus.valueOf(ErrorStatus.of(e.code()));
        if (status.is5xxServerError()) {
            log.error("Internal consistency failure {}: {}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(status).body(body(status, e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted().reduce((a, b) -> a + "; " + b).orElse("invalid request");
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> header(MissingRequestHeaderException e) {
        return respond(HttpStatus.BAD_REQUEST, "HEADER_REQUIRED", "Missing header: " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Request body could not be read");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        // A database constraint refused the change. The constraint name is in the log, not in the response.
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "DATA_CONFLICT", "The change conflicts with existing data");
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(body(status, code, message));
    }

    private static Map<String, Object> body(HttpStatus status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("status", status.value());
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
