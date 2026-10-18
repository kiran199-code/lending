package com.bank.lending.iam.config;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.auth.Authenticator;
import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.keys.KeyService;
import com.bank.lending.iam.web.CorrelationFilter;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

/**
 * Three filter chains, most specific first:
 * <ol>
 *   <li>the authorization server (/oauth2/*, /.well-known/*, /userinfo, /connect/*): codes, tokens, key set, discovery;</li>
 *   <li>the API (/api/**): bearer tokens only, no session, no CSRF; permissions are checked per endpoint by {@code Guard};</li>
 *   <li>everything else: the sign-in pages with a session and CSRF protection.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // ------------------------------------------------------------------ beans of the authorization server
    @Bean
    public RegisteredClientRepository registeredClientRepository(ClientRepository clients, Clock clock, IamSettings settings) {
        return new IamRegisteredClientRepository(clients, clock, settings.authCodeSeconds);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(IamSettings settings) {
        return AuthorizationServerSettings.builder().issuer(settings.issuer).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(KeyService keys) {
        return new IamJwkSource(keys);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(AccessService access, KeyService keys) {
        return new IamTokenCustomizer(access, keys);
    }

    @Bean
    public ClientSecretEncoder clientSecretEncoder(com.bank.lending.iam.common.SecretHasher hasher) {
        return new ClientSecretEncoder(hasher);
    }

    // ------------------------------------------------------------------ 1. authorization server
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerChain(HttpSecurity http, ClientSecretEncoder clientSecretEncoder,
                                                        @Value("${iam.security.hsts}") boolean hsts) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults())
                .clientAuthentication(ca -> ca.authenticationProviders(providers -> {
                    for (var p : providers) {
                        if (p instanceof ClientSecretAuthenticationProvider csp) csp.setPasswordEncoder(clientSecretEncoder);
                    }
                }));
        http
                .addFilterBefore(new CorrelationFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new ReauthFilter(), SecurityContextHolderFilter.class)
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))   // lets /userinfo accept the access token
                .cors(Customizer.withDefaults());
        headers(http, hsts);
        return http.build();
    }

    // ------------------------------------------------------------------ 2. the API
    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, @Value("${iam.security.hsts}") boolean hsts) throws Exception {
        http
                .securityMatcher("/api/**")
                .addFilterBefore(new CorrelationFilter(), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults());
        headers(http, hsts);
        return http.build();
    }

    // ------------------------------------------------------------------ 3. sign-in pages
    @Bean
    @Order(3)
    public SecurityFilterChain pagesChain(HttpSecurity http, Authenticator authenticator,
                                          @Value("${iam.security.hsts}") boolean hsts) throws Exception {
        http
                .addFilterBefore(new CorrelationFilter(), SecurityContextHolderFilter.class)
                .authenticationProvider(new IamAuthenticationProvider(authenticator))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/login", "/change-password", "/enrol", "/enrol/**", "/consent", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll())
                .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler(new LoginHandlers.Success())
                        .failureHandler(new LoginHandlers.Failure())
                        .permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login"))
                .cors(Customizer.withDefaults());
        headers(http, hsts);
        return http.build();
    }

    /** Headers for every chain. No form-action directive: the sign-in form must be allowed to redirect to the calling application. */
    private static void headers(HttpSecurity http, boolean hsts) throws Exception {
        http.headers(h -> {
            h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'"));
            h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
            h.frameOptions(f -> f.deny());
            if (hsts) h.httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000));
            else h.httpStrictTransportSecurity(s -> s.disable());
        });
    }

    // ------------------------------------------------------------------ CORS
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${iam.cors.allowed-origins}") String origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(SettingsConfig.csv(origins).stream().toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cfg.setExposedHeaders(List.of("X-Correlation-Id"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        src.registerCorsConfiguration("/oauth2/token", cfg);
        src.registerCorsConfiguration("/oauth2/jwks", cfg);
        src.registerCorsConfiguration("/oauth2/revoke", cfg);
        src.registerCorsConfiguration("/userinfo", cfg);
        src.registerCorsConfiguration("/.well-known/**", cfg);
        return src;
    }
}
