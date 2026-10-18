package com.bank.lending.iam.crypto;

import com.bank.lending.iam.domain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoTest {
    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    void roundTripsAndNeverRepeatsACiphertext() {
        SecretCrypto c = new SecretCrypto(key(7));
        String a = c.encrypt("JBSWY3DPEHPK3PXP", "mfa:42");
        String b = c.encrypt("JBSWY3DPEHPK3PXP", "mfa:42");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a, "mfa:42")).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(a.startsWith("v1:")).isTrue();
    }

    @Test
    void aValueCopiedToAnotherRowDoesNotDecrypt() {
        SecretCrypto c = new SecretCrypto(key(7));
        String stored = c.encrypt("secret", "mfa:42");
        assertThat(c.canDecrypt(stored, "mfa:43")).isFalse();
        assertThatThrownBy(() -> c.decrypt(stored, "mfa:43")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyOrAlteredTextDoesNotDecrypt() {
        SecretCrypto c = new SecretCrypto(key(7));
        String stored = c.encrypt("secret", "ctx");
        assertThat(new SecretCrypto(key(8)).canDecrypt(stored, "ctx")).isFalse();
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        assertThat(c.canDecrypt("v1:" + Base64.getEncoder().encodeToString(raw), "ctx")).isFalse();
        assertThat(c.canDecrypt("not encrypted", "ctx")).isFalse();
        assertThat(c.canDecrypt(null, "ctx")).isFalse();
    }

    @Test
    void theKeyMustBeExactly32BytesAndConfigured() {
        assertThatThrownBy(() -> new SecretCrypto(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCrypto.fromBase64("")).isInstanceOf(IllegalStateException.class).hasMessageContaining("IAM_ENCRYPTION_KEY");
        assertThatThrownBy(() -> SecretCrypto.fromBase64("!!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCrypto.fromBase64(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
        SecretCrypto ok = SecretCrypto.fromBase64(Base64.getEncoder().encodeToString(key(1)));
        assertThat(ok.decrypt(ok.encrypt("x", "c"), "c")).isEqualTo("x");
    }

    @Test
    void signingKeysAreStoredEncryptedBoundToTheirKeyIdAndReallySign() throws Exception {
        SecretCrypto c = new SecretCrypto(key(9));
        SigningKeys.Generated g = SigningKeys.generate(c);
        assertThat(g.privateEnc().startsWith("v1:")).isTrue();
        assertThat(g.publicPem()).contains("BEGIN PUBLIC KEY");
        PrivateKey priv = SigningKeys.privateKey(c, g.keyId(), g.privateEnc());
        PublicKey pub = SigningKeys.publicKey(g.publicPem());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update("payload".getBytes());
        byte[] sig = s.sign();
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(pub);
        v.update("payload".getBytes());
        assertThat(v.verify(sig)).isTrue();

        // the encrypted private key of one id does not open under another id
        assertThatThrownBy(() -> SigningKeys.privateKey(c, "other-kid", g.privateEnc())).isInstanceOf(IllegalStateException.class);
    }
}
