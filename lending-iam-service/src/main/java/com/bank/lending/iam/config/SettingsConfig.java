package com.bank.lending.iam.config;

import com.bank.lending.iam.common.MetricsSink;
import com.bank.lending.iam.common.SecretHasher;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.domain.LockoutPolicy;
import com.bank.lending.iam.domain.PasswordPolicy;
import com.bank.lending.iam.domain.TotpService;
import com.bank.lending.iam.events.HttpSender;
import com.bank.lending.iam.events.JdkHttpSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns application.yml into the plain objects the rest of the service uses. */
@Configuration
public class SettingsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IamSettings iamSettings(
            @Value("${iam.issuer}") String issuer,
            @Value("${iam.mfa.issuer-label}") String mfaLabel,
            @Value("${iam.tokens.activation-hours}") int activationHours,
            @Value("${iam.tokens.temp-password-hours}") int tempPasswordHours,
            @Value("${iam.tokens.client-secret-grace-minutes}") int graceMinutes,
            @Value("${iam.mfa.step-up-window-minutes}") int stepUpMinutes,
            @Value("${iam.keys.retired-key-published-hours}") int retiredKeyHours,
            @Value("${iam.keys.cache-seconds}") int keyCacheSeconds,
            @Value("${iam.tokens.staff-access-seconds}") int staffAccess,
            @Value("${iam.tokens.staff-refresh-seconds}") int staffRefresh,
            @Value("${iam.tokens.customer-access-seconds}") int customerAccess,
            @Value("${iam.tokens.customer-refresh-seconds}") int customerRefresh,
            @Value("${iam.tokens.service-access-seconds}") int serviceAccess,
            @Value("${iam.tokens.auth-code-seconds}") int authCode,
            @Value("${iam.audit.audit-denials}") boolean auditDenials,
            @Value("${iam.authz.account-bound-permissions}") String accountBound,
            @Value("${iam.webhooks.max-attempts}") int webhookAttempts,
            @Value("${iam.webhooks.timeout-seconds}") int webhookTimeout,
            @Value("${iam.webhooks.allow-http}") boolean allowHttp,
            @Value("${iam.bootstrap.username}") String bootstrapUser,
            @Value("${iam.bootstrap.password}") String bootstrapPassword,
            @Value("${iam.bootstrap.email}") String bootstrapEmail) {
        IamSettings s = new IamSettings();
        s.issuer = stripTrailingSlash(issuer);
        s.mfaIssuerLabel = mfaLabel;
        s.activationHours = activationHours;
        s.tempPasswordHours = tempPasswordHours;
        s.clientSecretGraceMinutes = graceMinutes;
        s.stepUpWindowMinutes = stepUpMinutes;
        s.retiredKeyPublishedHours = retiredKeyHours;
        s.keyCacheSeconds = keyCacheSeconds;
        s.staffAccessSeconds = staffAccess;
        s.staffRefreshSeconds = staffRefresh;
        s.customerAccessSeconds = customerAccess;
        s.customerRefreshSeconds = customerRefresh;
        s.serviceAccessSeconds = serviceAccess;
        s.authCodeSeconds = authCode;
        s.auditDenials = auditDenials;
        s.accountBoundPermissions = csv(accountBound);
        s.webhookMaxAttempts = webhookAttempts;
        s.webhookTimeoutSeconds = webhookTimeout;
        s.allowHttpWebhooks = allowHttp;
        s.bootstrapUsername = bootstrapUser;
        s.bootstrapPassword = bootstrapPassword;
        s.bootstrapEmail = bootstrapEmail;
        return s;
    }

    @Bean
    public SecretCrypto secretCrypto(@Value("${iam.encryption-key}") String key) {
        return SecretCrypto.fromBase64(key);   // refuses to start without a valid key
    }

    @Bean
    public SecretHasher secretHasher(@Value("${iam.password.bcrypt-cost}") int cost) {
        return new BcryptSecretHasher(cost);
    }

    @Bean
    public PasswordPolicy passwordPolicy(@Value("${iam.password.min-length}") int minLength, @Value("${iam.password.min-classes}") int minClasses,
                                         @Value("${iam.password.history}") int history, @Value("${iam.password.max-age-days-staff}") int maxAgeStaff,
                                         @Value("${iam.password.max-age-days-customer}") int maxAgeCustomer) {
        return new PasswordPolicy(minLength, minClasses, history, maxAgeStaff, maxAgeCustomer);
    }

    @Bean
    public LockoutPolicy lockoutPolicy(@Value("${iam.lockout.max-failures}") int maxFailures, @Value("${iam.lockout.lock-minutes}") int minutes) {
        return new LockoutPolicy(maxFailures, Duration.ofMinutes(minutes));
    }

    @Bean
    public TotpService totpService() {
        return new TotpService();
    }

    @Bean
    public HttpSender httpSender(IamSettings settings) {
        return new JdkHttpSender(settings.allowHttpWebhooks);
    }

    @Bean
    public MetricsSink metricsSink(MeterRegistry registry) {
        return new MicrometerMetrics(registry);
    }

    private static String stripTrailingSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static Set<String> csv(String s) {
        if (s == null) return Set.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
