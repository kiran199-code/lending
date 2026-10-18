package com.bank.lending.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The admin user (ADMIN_USERNAME / ADMIN_PASSWORD, can do everything) plus up to three operations users: batch, loan and limits
 * (BATCH_OPS_*, LOAN_OPS_*, LIMITS_OPS_*; each exists only when its password is set). What each may call is in {@link AccessPolicy}.
 * HTTP Basic, stateless. The React app keeps
 * the credentials for the browser session and sends them on every call. A 401 is returned without
 * a WWW-Authenticate header so the browser never pops its own login dialog; the app shows its own.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Login required\"}");
        };
        AccessDeniedHandler forbidden = (request, response, ex) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Your login does not have access to this function\"}");
        };
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    for (AccessPolicy.Rule r : AccessPolicy.RULES) {
                        var spec = r.method() == null ? auth.requestMatchers(r.pattern()) : auth.requestMatchers(HttpMethod.valueOf(r.method()), r.pattern());
                        if (r.roles().isEmpty()) {
                            spec.authenticated();
                        } else {
                            String[] roles = new String[r.roles().size() + 1];
                            roles[0] = AccessPolicy.ADMIN;
                            for (int i = 0; i < r.roles().size(); i++) {
                                roles[i + 1] = r.roles().get(i);
                            }
                            spec.hasAnyRole(roles);
                        }
                    }
                    // anything not listed (accounting, maintenance, new endpoints) is for ADMIN only
                    auth.requestMatchers("/api/**").hasRole(AccessPolicy.ADMIN)
                            // Static React bundle, health probe and API docs pages (data calls still need login).
                            .anyRequest().permitAll();
                })
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(@Value("${admin.username}") String username,
                                                 @Value("${admin.password}") String password,
                                                 @Value("${admin.batch-ops.username:batch-ops}") String batchUser,
                                                 @Value("${admin.batch-ops.password:}") String batchPassword,
                                                 @Value("${admin.loan-ops.username:loan-ops}") String loanUser,
                                                 @Value("${admin.loan-ops.password:}") String loanPassword,
                                                 @Value("${admin.limits-ops.username:limits-ops}") String limitsUser,
                                                 @Value("${admin.limits-ops.password:}") String limitsPassword) {
        UserDetails[] users = AdminUsers.build(username, password, batchUser, batchPassword, loanUser, loanPassword, limitsUser, limitsPassword)
                .stream()
                .map(u -> User.withUsername(u.username()).password("{noop}" + u.password()).roles(u.role()).build())
                .toArray(UserDetails[]::new);
        return new InMemoryUserDetailsManager(users);
    }
}
