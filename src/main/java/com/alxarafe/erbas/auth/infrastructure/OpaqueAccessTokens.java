package com.alxarafe.erbas.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import com.alxarafe.erbas.auth.application.LoginUseCase.TokenIssuer;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore.TokenIdentity;

public final class OpaqueAccessTokens implements TokenIssuer {

    private final JdbcAuthenticationStore store;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public OpaqueAccessTokens(JdbcAuthenticationStore store, Clock clock, Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Access token TTL must be between 1 second and 30 days");
        }
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String issue(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var created = clock.instant().truncatedTo(ChronoUnit.MICROS);
        store.storeToken(digest(raw), userId, created, created.plus(ttl));
        return raw;
    }

    public Optional<TokenIdentity> findIdentity(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        return store.findUsableToken(digest(rawToken), clock.instant());
    }

    private static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", unavailable);
        }
    }
}
