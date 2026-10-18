package com.bank.lending.iam.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TotpAndRecoveryTest {
    private final TotpService totp = new TotpService();
    /** RFC 6238 test secret "12345678901234567890" */
    private static final String RFC_SECRET = TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238Vectors() {
        // RFC 6238 appendix B (SHA-1), last six of the eight digits
        assertThat(totp.code(RFC_SECRET, totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(totp.code(RFC_SECRET, totp.stepAt(Instant.ofEpochSecond(1111111109L)))).isEqualTo("081804");
        assertThat(totp.code(RFC_SECRET, totp.stepAt(Instant.ofEpochSecond(1234567890L)))).isEqualTo("005924");
    }

    @Test
    void base32RoundTrips() {
        byte[] data = "hello totp world".getBytes(StandardCharsets.UTF_8);
        assertThat(new String(TotpService.base32Decode(TotpService.base32Encode(data)), StandardCharsets.UTF_8)).isEqualTo("hello totp world");
    }

    @Test
    void acceptsOneStepOfDriftEitherSideButNotTwo() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = totp.stepAt(now);
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step), now, null).status()).isEqualTo(TotpService.Status.OK);
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step - 1), now, null).status()).isEqualTo(TotpService.Status.OK);
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step + 1), now, null).status()).isEqualTo(TotpService.Status.OK);
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step - 2), now, null).status()).isEqualTo(TotpService.Status.INVALID);
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step + 2), now, null).status()).isEqualTo(TotpService.Status.INVALID);
    }

    @Test
    void aStepAlreadyUsedIsAReplay() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = totp.stepAt(now);
        String code = totp.code(RFC_SECRET, step);
        TotpService.Result first = totp.verify(RFC_SECRET, code, now, null);
        assertThat(first.status()).isEqualTo(TotpService.Status.OK);
        assertThat(totp.verify(RFC_SECRET, code, now, first.step()).status()).isEqualTo(TotpService.Status.REPLAY);
        // an earlier step than the last used one is a replay too
        assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, step - 1), now, first.step()).status()).isEqualTo(TotpService.Status.REPLAY);
    }

    @Test
    void malformedCodesAreInvalid() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        for (String bad : new String[]{null, "", "12345", "1234567", "abcdef", "12 34 5x"}) {
            assertThat(totp.verify(RFC_SECRET, bad, now, null).status()).isEqualTo(TotpService.Status.INVALID);
        }
        // spaces inside a good code are tolerated
        String good = totp.code(RFC_SECRET, totp.stepAt(now));
        assertThat(totp.verify(RFC_SECRET, good.substring(0, 3) + " " + good.substring(3), now, null).status()).isEqualTo(TotpService.Status.OK);
    }

    @Test
    void otpauthLinkCarriesIssuerAccountAndSecretEncoded() {
        String uri = totp.otpauthUri("Lending Platform", "a.b@bank", "ABCDEF234567");
        assertThat(uri).contains("otpauth://totp/Lending%20Platform:a.b%40bank");
        assertThat(uri).contains("secret=ABCDEF234567");
        assertThat(uri).contains("digits=6");
    }

    @Test
    void newSecretsAreBase32AndDifferent() {
        String a = totp.newSecret(), b = totp.newSecret();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.matches("[A-Z2-7]{32}")).isTrue();
    }

    @Test
    void recoveryCodesAreEightDistinctElevenCharacterCodes() {
        List<String> codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(8);
        assertThat(new HashSet<>(codes).size()).isEqualTo(8);
        for (String c : codes) {
            assertThat(c.length()).isEqualTo(11);
            assertThat(RecoveryCodes.looksLikeRecoveryCode(c)).isTrue();
        }
    }

    @Test
    void recoveryCodeHashIgnoresCaseAndSpaces() {
        assertThat(RecoveryCodes.hash("abcde-23456")).isEqualTo(RecoveryCodes.hash(" ABCDE-23456 "));
        assertThat(RecoveryCodes.hash("ABCDE-23456")).isNotEqualTo(RecoveryCodes.hash("ABCDE-23457"));
        assertThat(RecoveryCodes.looksLikeRecoveryCode("123456")).isFalse();
    }
}
