package com.alxarafe.erbas;

import java.time.Instant;

import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AuthenticationPersistenceIT {

    // Synthetic encoded fixture only; this task does not implement password hashing.
    private static final String PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String TOKEN_HASH = "ab".repeat(32);
    private static final Instant CREATED = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(3600);

    @Autowired private JdbcAuthenticationStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void migrationsRunExactlyOnceWithoutAuthenticationSeedData() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erbas_persistence_marker WHERE marker_id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_access_token", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'auth_access_token'",
                String.class)).contains("auth_access_token_pkey", "auth_access_token_user_id_idx",
                "auth_access_token_expires_at_idx");
    }

    @Test
    @Transactional
    void credentialsRoundTripUnchangedAndOnlyEncodedPasswordIsStored() {
        String email = " Case-sensitive@example.test ";
        long id = store.createUser(email, PASSWORD_HASH, true);
        var user = store.findUserByEmail(email).orElseThrow();
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.email()).isEqualTo(email);
        assertThat(user.passwordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(user.enabled()).isTrue();
        assertThat(user.toString()).doesNotContain(PASSWORD_HASH, email);
        assertThat(store.findUserByEmail(email.trim())).isEmpty();
        assertThat(store.findUserByEmail("absent@example.test")).isEmpty();
    }

    @Test
    @Transactional
    void tokenDigestResolvesIdentityUntilExactExpiryAndRequiresEnabledUser() {
        long id = store.createUser("token@example.test", PASSWORD_HASH, true);
        store.storeToken(TOKEN_HASH, id, CREATED, EXPIRES);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_access_token WHERE user_id = ?",
                String.class, id)).isEqualTo(TOKEN_HASH);
        assertThat(jdbc.queryForObject("SELECT created_at FROM auth_access_token WHERE user_id = ?",
                java.sql.Timestamp.class, id).toInstant()).isEqualTo(CREATED);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_access_token WHERE user_id = ?",
                java.sql.Timestamp.class, id).toInstant()).isEqualTo(EXPIRES);
        assertThat(store.findUsableToken(TOKEN_HASH, EXPIRES.minusSeconds(1))).isPresent();
        assertThat(store.findUsableToken(TOKEN_HASH, CREATED).orElseThrow().userId()).isEqualTo(id);
        assertThat(store.findUsableToken(TOKEN_HASH, EXPIRES)).isEmpty();
        assertThat(store.findUsableToken(TOKEN_HASH, EXPIRES.plusSeconds(1))).isEmpty();
        assertThat(store.findUsableToken("cd".repeat(32), CREATED)).isEmpty();
        jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", id);
        assertThat(store.findUsableToken(TOKEN_HASH, CREATED)).isEmpty();
        assertThat(store.findUserByEmail("token@example.test").orElseThrow().enabled()).isFalse();
    }

    @Test
    @Transactional
    void duplicateEmailIsRejected() {
        store.createUser("unique@example.test", PASSWORD_HASH, true);
        assertThatThrownBy(() -> store.createUser("unique@example.test", PASSWORD_HASH, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void tokenMustReferenceExistingUser() {
        assertThatThrownBy(() -> store.storeToken(TOKEN_HASH, Long.MAX_VALUE, CREATED, EXPIRES))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void duplicateTokenDigestIsRejected() {
        long id = store.createUser("unique-token@example.test", PASSWORD_HASH, true);
        store.storeToken(TOKEN_HASH, id, CREATED, EXPIRES);
        assertThatThrownBy(() -> store.storeToken(TOKEN_HASH, id, CREATED, EXPIRES))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void nonDigestTokenFormatIsRejected() {
        long id = store.createUser("digest@example.test", PASSWORD_HASH, true);
        assertThatThrownBy(() -> store.storeToken("raw-bearer-token", id, CREATED, EXPIRES))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void nonPositiveLifetimeIsRejected() {
        long id = store.createUser("expiry@example.test", PASSWORD_HASH, true);
        assertThatThrownBy(() -> store.storeToken(TOKEN_HASH, id, CREATED, CREATED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO auth_user (email, password_hash, enabled) VALUES (NULL, 'encoded', true)",
            "INSERT INTO auth_user (email, password_hash, enabled) VALUES ('', 'encoded', true)",
            "INSERT INTO auth_user (email, password_hash, enabled) VALUES ('test', NULL, true)",
            "INSERT INTO auth_user (email, password_hash, enabled) VALUES ('test', '', true)",
            "INSERT INTO auth_user (email, password_hash, enabled) VALUES ('test', 'encoded', NULL)",
            "INSERT INTO auth_user (email, password_hash, enabled, admin) VALUES ('test', 'encoded', true, NULL)"
    })
    @Transactional
    void requiredUserFieldsAreEnforced(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void referencedUserCannotBeDeletedImplicitly() {
        long id = store.createUser("referenced@example.test", PASSWORD_HASH, true);
        store.storeToken(TOKEN_HASH, id, CREATED, EXPIRES);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auth_user WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
