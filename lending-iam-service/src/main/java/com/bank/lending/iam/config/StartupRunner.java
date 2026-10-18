package com.bank.lending.iam.config;

import com.bank.lending.iam.auth.BootstrapService;
import com.bank.lending.iam.auth.ReencryptService;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.keys.KeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * What must be true before the first request: a signing key exists, secrets are under the current encryption key
 * (when the previous key is supplied), and the first administrator exists on an empty system.
 */
@Component
public class StartupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final KeyService keys;
    private final ReencryptService reencrypt;
    private final BootstrapService bootstrap;
    private final SecretCrypto current;
    private final String previousKey;

    public StartupRunner(KeyService keys, ReencryptService reencrypt, BootstrapService bootstrap, SecretCrypto current,
                         @Value("${iam.encryption-key-previous:}") String previousKey) {
        this.keys = keys;
        this.reencrypt = reencrypt;
        this.bootstrap = bootstrap;
        this.current = current;
        this.previousKey = previousKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        RequestContext.runAsSystem("startup", () -> {
            if (previousKey != null && !previousKey.isBlank()) {
                ReencryptService.Result r = reencrypt.run(current, SecretCrypto.fromBase64(previousKey));
                log.info("Encryption key rotation: {} MFA secrets, {} signing keys, {} webhook secrets re-encrypted, {} unreadable",
                        r.mfaSecrets(), r.signingKeys(), r.webhookSecrets(), r.unreadable());
            }
            keys.ensureActiveKey();
            if (bootstrap.run()) log.warn("The bootstrap administrator was created. Create two named administrators, then suspend it (see the runbook).");
        });
    }
}
