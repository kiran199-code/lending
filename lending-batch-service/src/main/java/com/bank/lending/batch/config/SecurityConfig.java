package com.bank.lending.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic auth in front of the admin/ops REST layer only — actuator health/prometheus stay
 * open for infra scraping. Credentials are a placeholder in-memory user configured via
 * batch.admin.username/password (see application.yml); swap for the organization's real
 * identity provider (OAuth2/JWT resource server, whatever fronts the other lending services)
 * before this goes anywhere near production — this is deliberately the simplest thing that
 * makes the endpoints not-open, not a production auth design.
 */
@Configuration
public class SecurityConfig {

    @Value("${batch.admin.username:batch-admin}")
    private String adminUsername;

    @Value("${batch.admin.password:changeme}")
    private String adminPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername)
                        .password(encoder.encode(adminPassword))
                        .roles("BATCH_ADMIN")
                        .build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                    .requestMatchers("/api/v1/batch-admin/**").hasRole("BATCH_ADMIN")
                    .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable()); // stateless admin API, no browser form posts
        return http.build();
    }
}
