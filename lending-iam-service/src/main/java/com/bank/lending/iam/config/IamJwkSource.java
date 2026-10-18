package com.bank.lending.iam.config;

import com.bank.lending.iam.keys.KeyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * The signing keys as the authorization server sees them: the active key with its private part, retired keys public-only
 * for as long as they are still published. Read through {@link KeyService}'s short cache, so a rotation shows within seconds.
 */
public class IamJwkSource implements JWKSource<SecurityContext> {
    private final KeyService keys;

    public IamJwkSource(KeyService keys) {
        this.keys = keys;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        List<JWK> all = new ArrayList<>();
        for (KeyService.Published p : keys.published()) {
            RSAKey.Builder b = new RSAKey.Builder((RSAPublicKey) p.publicKey()).keyID(p.keyId()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256);
            if (p.privateKey() != null) b.privateKey((RSAPrivateKey) p.privateKey());
            all.add(b.build());
        }
        return selector.select(new JWKSet(all));
    }
}
