package com.alxarafe.erbas.auth.infrastructure;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordAndTokenPolicyTests {

    @Test
    void standardEncoderUsesRandomSaltAndEntireLongUnicodePassword() {
        var encoder = new AuthenticationConfiguration().passwordEncoder();
        String password = " \t" + "密".repeat(40) + "x".repeat(256) + "\n ";
        String hash = encoder.encode(password);
        assertThat(hash.startsWith("{pbkdf2-sha256-600000}")).isTrue();
        assertThat(hash.equals(password)).isFalse();
        assertThat(encoder.encode(password).equals(hash)).isFalse();
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches(password + "different-suffix", hash)).isFalse();
        assertThat(encoder.matches(password.trim(), hash)).isFalse();
    }

    @Test
    void dummyAndUnusableHashesRunRealComparisonButAlwaysFail() {
        var encoder = spy(new AuthenticationConfiguration().passwordEncoder());
        var verifier = new SpringPasswordVerifier(encoder);
        clearInvocations(encoder);
        assertThat(verifier.matches("dummy-password-not-an-account", null)).isFalse();
        verify(encoder).matches(eq("dummy-password-not-an-account"), startsWith("{pbkdf2-sha256-600000}"));
        clearInvocations(encoder);
        assertThat(verifier.matches("input", "unsupported-encoding")).isFalse();
        verify(encoder).matches(eq("input"), startsWith("{pbkdf2-sha256-600000}"));
        assertThat(verifier.matches("input", "{pbkdf2-sha256-600000}00")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.5S", "P31D"})
    void invalidInternalTtlFailsAtConstruction(String ttl) {
        assertThatThrownBy(() -> new OpaqueAccessTokens(mock(JdbcAuthenticationStore.class), Clock.systemUTC(),
                Duration.parse(ttl))).isInstanceOf(IllegalArgumentException.class);
    }
}
