package com.alxarafe.erbas.auth.application;

import java.util.Optional;

/** Credential checking and token issuance, independent of HTTP and Spring. */
public final class LoginUseCase {

    private final CredentialLookup credentials;
    private final PasswordVerifier passwords;
    private final TokenIssuer tokens;

    public LoginUseCase(CredentialLookup credentials, PasswordVerifier passwords, TokenIssuer tokens) {
        this.credentials = credentials;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    public Optional<String> login(String email, String password) {
        var user = credentials.findUserByEmail(email);
        // Always invoke password verification, including unknown and disabled users.
        boolean matches = passwords.matches(password, user.map(Credentials::passwordHash).orElse(null));
        if (user.isEmpty() || !matches || !user.orElseThrow().enabled()) {
            return Optional.empty();
        }
        return Optional.of(tokens.issue(user.orElseThrow().id()));
    }

    public interface CredentialLookup {
        Optional<Credentials> findUserByEmail(String email);
    }

    public interface PasswordVerifier {
        /** A null encoded password means the user is unknown; still perform a costly comparison. */
        boolean matches(String rawPassword, String encodedPassword);
    }

    public interface TokenIssuer {
        String issue(long userId);
    }

    public record Credentials(long id, String email, String passwordHash, boolean enabled) {
        @Override
        public String toString() {
            return "Credentials[id=" + id + ", enabled=" + enabled + "]";
        }
    }
}
