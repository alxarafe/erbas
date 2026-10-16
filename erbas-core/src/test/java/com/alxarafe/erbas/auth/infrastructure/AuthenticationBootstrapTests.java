package com.alxarafe.erbas.auth.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthenticationBootstrapTests {

    @Test
    void disabledByDefaultEvenWithCredentialsPresent() {
        new ApplicationContextRunner().withUserConfiguration(AuthenticationBootstrapConfiguration.class)
                .withPropertyValues("erbas.auth.bootstrap.email=local@example.test",
                        "erbas.auth.bootstrap.password=local-demo-only")
                .run(context -> assertThat(context.containsBean("authenticationBootstrap")).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "production", "development,production", "validation,development"})
    void refusesUnsupportedOrMixedProfilesBeforeAccessingDatabase(String profiles) {
        var environment = new MockEnvironment();
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        var store = mock(JdbcAuthenticationStore.class);
        var encoder = mock(PasswordEncoder.class);
        var runner = new AuthenticationBootstrapConfiguration()
                .authenticationBootstrap(store, encoder, environment, "local@example.test", "local-demo-only", false);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authentication bootstrap requires only validation or development profile");
        verifyNoInteractions(store, encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password", "both"})
    void missingCredentialsNeverCreateData(String missing) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("validation");
        var store = mock(JdbcAuthenticationStore.class);
        var encoder = mock(PasswordEncoder.class);
        var runner = new AuthenticationBootstrapConfiguration().authenticationBootstrap(store, encoder, environment,
                missing.equals("password") ? "validation@example.test" : "",
                missing.equals("email") ? "validation-only" : "", false);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authentication bootstrap requires nonempty email and password");
        verifyNoInteractions(store, encoder);
    }
}
