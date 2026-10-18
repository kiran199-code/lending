package com.bank.lending.iam.config;

import com.bank.lending.iam.common.SecretHasher;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigGlueTest {
    private final SecretHasher hasher = new SecretHasher() {
        public String hash(String raw) { return "{bcrypt}$2a$11$" + raw; }
        public boolean matches(String raw, String hash) { return hash != null && hash.equals("{bcrypt}$2a$11$" + raw); }
    };

    @Test
    void clientSecretEncoderAcceptsOneHashOrTheGracePair() {
        ClientSecretEncoder e = new ClientSecretEncoder(hasher);
        String current = hasher.hash("new-secret");
        String previous = hasher.hash("old-secret");
        assertThat(e.matches("new-secret", current)).isTrue();
        assertThat(e.matches("old-secret", current)).isFalse();
        String pair = ClientSecretEncoder.DUAL + current + "~" + previous;
        assertThat(e.matches("new-secret", pair)).isTrue();
        assertThat(e.matches("old-secret", pair)).isTrue();
        assertThat(e.matches("third", pair)).isFalse();
        assertThat(e.matches(null, pair)).isFalse();
        assertThat(e.matches("x", null)).isFalse();
    }

    @Test
    void clientSecretEncoderNeverAsksForReEncoding() {
        ClientSecretEncoder e = new ClientSecretEncoder(hasher);
        assertThat(e.upgradeEncoding("{bcrypt}$2a$04$whatever")).isFalse();
        assertThat(e.upgradeEncoding(ClientSecretEncoder.DUAL + "a~b")).isFalse();
    }

    @Test
    void mfaTimeIsReadFromTheSignInAuthorities() {
        List<GrantedAuthority> a = List.of(new SimpleGrantedAuthority("ROLE_STAFF"), new SimpleGrantedAuthority("AMR_pwd"),
                new SimpleGrantedAuthority("AMR_otp"), new SimpleGrantedAuthority("MFA_AT_1760000123"));
        assertThat(IamTokenCustomizer.mfaAt(a)).isEqualTo(1760000123L);
        assertThat(IamTokenCustomizer.mfaAt(List.of(new SimpleGrantedAuthority("ROLE_STAFF")))).isNull();
        assertThat(IamTokenCustomizer.mfaAt(List.of(new SimpleGrantedAuthority("MFA_AT_notanumber")))).isNull();
    }

    @Test
    void promptLoginIsRecognisedAmongOtherPromptValues() {
        assertThat(ReauthFilter.wantsLogin("login")).isTrue();
        assertThat(ReauthFilter.wantsLogin("consent login")).isTrue();
        assertThat(ReauthFilter.wantsLogin("none")).isFalse();
        assertThat(ReauthFilter.wantsLogin("consent")).isFalse();
        assertThat(ReauthFilter.wantsLogin(null)).isFalse();
        assertThat(ReauthFilter.wantsLogin("")).isFalse();
    }

    @Test
    void settingsListsAreTrimmedAndDeduplicated() {
        assertThat(SettingsConfig.csv(" a , b,,a ")).hasSize(2);
        assertThat(SettingsConfig.csv(null)).isEmpty();
        assertThat(SettingsConfig.csv("")).isEmpty();
    }
}
