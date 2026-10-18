package com.bank.lending.iam.web;

import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One error shape for every failure: {status, error, message, code, correlationId, details?}. Never a stack trace, SQL text or class name. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IamException.class)
    public ResponseEntity<Map<String, Object>> iam(IamException e) {
        return body(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is not valid", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read", List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> header(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, "HEADER_REQUIRED", "Missing header: " + e.getHeaderName(), List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This method is not supported here", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        log.warn("Data conflict [{}]: {}", RequestContext.current().correlationId(), e.getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, "DATA_CONFLICT", "The change conflicts with existing data", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        // the cause goes to the log with the correlation id; the caller gets nothing that identifies code or data
        log.error("Unexpected error [{}]", RequestContext.current().correlationId(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Quote the correlation id to the administrator.", List.of());
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, List<String> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("status", status.value());
        m.put("error", status.getReasonPhrase());
        m.put("code", code);
        m.put("message", message);
        m.put("correlationId", RequestContext.current().correlationId());
        if (details != null && !details.isEmpty()) m.put("details", details);
        return ResponseEntity.status(status).body(m);
    }
}
