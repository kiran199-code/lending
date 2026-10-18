package com.bank.lending.iam.config;

import java.util.Set;

/**
 * Every tunable of the service in one plain object (filled from application.yml by {@link SettingsConfig}).
 * Defaults are the values of the specification.
 */
public class IamSettings {
    public String issuer = "http://localhost:8085";
    public String mfaIssuerLabel = "Lending Platform";

    public int activationHours = 72;
    public int tempPasswordHours = 24;
    public int clientSecretGraceMinutes = 30;
    public int stepUpWindowMinutes = 15;
    public int retiredKeyPublishedHours = 24;
    public int keyCacheSeconds = 30;

    public int staffAccessSeconds = 900;
    public int staffRefreshSeconds = 28800;
    public int customerAccessSeconds = 600;
    public int customerRefreshSeconds = 1800;
    public int serviceAccessSeconds = 600;
    public int authCodeSeconds = 300;

    public boolean auditDenials = true;
    public Set<String> accountBoundPermissions = Set.of("portal.account.read", "portal.payment.initiate", "portal.statement.read", "lcm.portal.read");

    public int webhookMaxAttempts = 8;
    public int webhookTimeoutSeconds = 5;
    public boolean allowHttpWebhooks = false;

    public String bootstrapUsername = "";
    public String bootstrapPassword = "";
    public String bootstrapEmail = "bootstrap@example.invalid";
}
