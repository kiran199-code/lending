package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.crypto.SecretCrypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** RSA key material for RS256: generation, PEM text and the encrypted form kept in the database. */
public final class SigningKeys {
    public record Generated(String keyId, String publicPem, String privateEnc) {}

    private SigningKeys() {}

    public static String contextFor(String keyId) {
        return "signing-key:" + keyId;
    }

    public static Generated generate(SecretCrypto crypto) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            String kid = "k" + Hashing.randomToken(9);
            String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            return new Generated(kid, pem("PUBLIC KEY", kp.getPublic().getEncoded()), crypto.encrypt(priv, contextFor(kid)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate a signing key", e);
        }
    }

    public static PrivateKey privateKey(SecretCrypto crypto, String keyId, String privateEnc) {
        try {
            byte[] der = Base64.getDecoder().decode(crypto.decrypt(privateEnc, contextFor(keyId)));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot read signing key " + keyId, e);
        }
    }

    public static PublicKey publicKey(String publicPem) {
        try {
            String b64 = publicPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot read public key", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }
}
