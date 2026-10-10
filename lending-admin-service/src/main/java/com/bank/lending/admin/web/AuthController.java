package com.bank.lending.admin.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import com.bank.lending.admin.service.StatementProxyService;

import java.util.Map;

@RestController
public class AuthController {

    private final StatementProxyService statements;
    private final String timezone;

    public AuthController(StatementProxyService statements, @Value("${admin.timezone:Asia/Kolkata}") String timezone) {
        this.statements = statements;
        this.timezone = timezone;
    }

    /** Used by the login screen to verify credentials, and to learn which optional features are on. */
    @GetMapping("/api/admin/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("username", authentication.getName(), "statementEnabled", statements.enabled(),
                "timezone", timezone);
    }
}
