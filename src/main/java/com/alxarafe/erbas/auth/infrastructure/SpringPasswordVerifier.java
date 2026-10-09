package com.alxarafe.erbas.auth.infrastructure;

import com.alxarafe.erbas.auth.application.LoginUseCase.PasswordVerifier;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class SpringPasswordVerifier implements PasswordVerifier {

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public SpringPasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode("dummy-password-not-an-account");
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        try {
            boolean result = encoder.matches(rawPassword, encodedPassword == null ? dummyHash : encodedPassword);
            return encodedPassword != null && result;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalidStoredHash) {
            // A corrupt/unsupported encoding is also unusable, with no cheap failure path.
            encoder.matches(rawPassword, dummyHash);
            return false;
        }
    }
}
