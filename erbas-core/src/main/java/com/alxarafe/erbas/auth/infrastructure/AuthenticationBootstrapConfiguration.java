package com.alxarafe.erbas.auth.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit local/ephemeral data bootstrap; Flyway remains schema-only. */
@Configuration(proxyBeanMethods = false)
public class AuthenticationBootstrapConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "erbas.auth.bootstrap", name = "enabled", havingValue = "true")
    public ApplicationRunner authenticationBootstrap(JdbcAuthenticationStore store, PasswordEncoder encoder,
            Environment environment, @Value("${erbas.auth.bootstrap.email}") String email,
            @Value("${erbas.auth.bootstrap.password}") String password,
            @Value("${erbas.auth.bootstrap.admin:false}") boolean admin) {
        // ApplicationRunner executes after context initialization, including Flyway migrations.
        return arguments -> {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length != 1 || !(profiles[0].equals("validation") || profiles[0].equals("development"))) {
                throw new IllegalStateException("Authentication bootstrap requires only validation or development profile");
            }
            if (email.isEmpty() || password.isEmpty()) {
                throw new IllegalStateException("Authentication bootstrap requires nonempty email and password");
            }
            var user = store.findUserByEmail(email);
            if (user.isEmpty()) {
                store.createBootstrapUserIfAbsent(email, encoder.encode(password), admin);
                user = store.findUserByEmail(email);
            }
            boolean matches = false;
            if (user.isPresent() && user.orElseThrow().enabled()
                    && store.findUserById(user.orElseThrow().id()).orElseThrow().admin() == admin) {
                try {
                    matches = encoder.matches(password, user.orElseThrow().passwordHash());
                } catch (IllegalArgumentException | IndexOutOfBoundsException unusableEncoding) {
                    // Report only bootstrap failure, without the encoder's stored-hash details.
                }
            }
            if (!matches) {
                throw new IllegalStateException("Authentication bootstrap cannot reuse the existing account");
            }
        };
    }
}
