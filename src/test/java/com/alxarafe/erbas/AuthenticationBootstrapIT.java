package com.alxarafe.erbas;

import com.alxarafe.erbas.auth.infrastructure.AuthenticationBootstrapConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationBootstrapIT {

    private static final String EMAIL = "bootstrap-local@example.test";
    private static final String PASSWORD = "local-demo-bootstrap-only";
    @Autowired private JdbcAuthenticationStore store;
    @Autowired private PasswordEncoder encoder;

    @Test
    void developmentProvisioningIsEncodedIdempotentAndSilent(CapturedOutput output) throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var runner = new AuthenticationBootstrapConfiguration()
                .authenticationBootstrap(store, encoder, environment, EMAIL, PASSWORD);
        runner.run(new DefaultApplicationArguments());
        var before = store.findUserByEmail(EMAIL).orElseThrow();
        assertThat(before.enabled()).isTrue();
        assertThat(before.passwordHash().equals(PASSWORD)).isFalse();
        assertThat(encoder.matches(PASSWORD, before.passwordHash())).isTrue();
        runner.run(new DefaultApplicationArguments());
        var after = store.findUserByEmail(EMAIL).orElseThrow();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.passwordHash().equals(before.passwordHash())).isTrue();
        assertThat(output.getAll().contains(PASSWORD)).isFalse();
        assertThat(output.getAll().contains(before.passwordHash())).isFalse();
    }

    @Test
    void existingAccountWithOtherPasswordIsNotOverwritten() {
        String hash = encoder.encode("different-local-password");
        long id = store.createUser(EMAIL, hash, true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var runner = new AuthenticationBootstrapConfiguration()
                .authenticationBootstrap(store, encoder, environment, EMAIL, PASSWORD);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authentication bootstrap cannot reuse the existing account");
        var after = store.findUserByEmail(EMAIL).orElseThrow();
        assertThat(after.id()).isEqualTo(id);
        assertThat(after.passwordHash().equals(hash)).isTrue();
    }

    @Test
    void disabledAccountIsNotReactivated() {
        store.createUser(EMAIL, encoder.encode(PASSWORD), false);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("validation");
        var runner = new AuthenticationBootstrapConfiguration()
                .authenticationBootstrap(store, encoder, environment, EMAIL, PASSWORD);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.findUserByEmail(EMAIL).orElseThrow().enabled()).isFalse();
    }

    @Test
    void concurrentInsertConflictNeverChangesExistingCredentials() {
        String hash = encoder.encode(PASSWORD);
        long id = store.createUser(EMAIL, hash, true);
        store.createBootstrapUserIfAbsent(EMAIL, encoder.encode("another-password"));
        var after = store.findUserByEmail(EMAIL).orElseThrow();
        assertThat(after.id()).isEqualTo(id);
        assertThat(after.passwordHash().equals(hash)).isTrue();
    }
}
