package com.bank.lending.lcm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One service credential (LCM_SERVICE_USERNAME / LCM_SERVICE_PASSWORD), HTTP Basic, stateless.
 * This is the swap point for the IAM integration: replace the filter chain with an OAuth2 resource server
 * (JWT from lending-iam-service), map the {@code lcm.*} permission codes (see IAM_PERMISSIONS.txt) to
 * authorities, and read the actor from the token subject instead of the X-Actor header.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Service credential required\"}");
        };
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        // Health probe and API docs pages; data calls above still need the credential.
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(@Value("${lcm.security.username}") String username,
                                                 @Value("${lcm.security.password}") String password) {
        UserDetails svc = User.withUsername(username).password("{noop}" + password).roles("LCM").build();
        return new InMemoryUserDetailsManager(svc);
    }
}
