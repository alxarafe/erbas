package com.alxarafe.erbas.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.alxarafe.erbas.auth.application.LoginUseCase.CredentialLookup;
import com.alxarafe.erbas.auth.application.LoginUseCase.Credentials;
import com.alxarafe.erbas.auth.application.UserIdentity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Persistence only: callers supply encoded passwords and SHA-256 token digests. */
@Repository
public class JdbcAuthenticationStore implements CredentialLookup {

    private final JdbcTemplate jdbc;

    public JdbcAuthenticationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createUser(String email, String passwordHash, boolean enabled) {
        return createUser(email, passwordHash, enabled, false);
    }

    public long createUser(String email, String passwordHash, boolean enabled, boolean admin) {
        return jdbc.queryForObject("""
                INSERT INTO auth_user (email, password_hash, enabled, admin)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, email, passwordHash, enabled, admin);
    }

    /** Bootstrap must never overwrite an existing account, including concurrent startup. */
    public void createBootstrapUserIfAbsent(String email, String passwordHash, boolean admin) {
        createUserIfEmailAvailable(email, passwordHash, admin);
    }

    /** Empty means exact email conflict, without aborting an enclosing transaction. */
    public Optional<UserIdentity> createUserIfEmailAvailable(String email, String passwordHash, boolean admin) {
        return jdbc.query("""
                INSERT INTO auth_user (email, password_hash, enabled, admin)
                VALUES (?, ?, true, ?) ON CONFLICT (email) DO NOTHING
                RETURNING id, email, enabled, admin
                """, (rs, row) -> new UserIdentity(rs.getLong("id"), rs.getString("email"),
                rs.getBoolean("enabled"), rs.getBoolean("admin")), email, passwordHash, admin)
                .stream().findFirst();
    }

    public Optional<UserIdentity> findUserById(long userId) {
        return jdbc.query("""
                SELECT id, email, enabled, admin FROM auth_user WHERE id = ?
                """, (rs, row) -> new UserIdentity(rs.getLong("id"), rs.getString("email"),
                rs.getBoolean("enabled"), rs.getBoolean("admin")), userId).stream().findFirst();
    }

    /** All administrative state changes must use this serialized transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserUpdateResult updateUserState(long userId, Boolean enabled, Boolean admin) {
        if (enabled == null && admin == null) {
            throw new IllegalArgumentException("At least one user state field is required");
        }
        // A joined higher-isolation transaction could otherwise retain a stale pre-lock snapshot.
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalStateException("User state updates require read committed isolation");
        }
        // Acquire before reading or writing any user rows. Normal identity reads stay unblocked.
        jdbc.execute("LOCK TABLE auth_user IN SHARE ROW EXCLUSIVE MODE");
        var existing = findUserById(userId);
        if (existing.isEmpty()) {
            return new UserUpdateResult(UserUpdateOutcome.NOT_FOUND, null);
        }
        var user = existing.orElseThrow();
        var updated = new UserIdentity(userId, user.email(), enabled == null ? user.enabled() : enabled,
                admin == null ? user.admin() : admin);
        if (user.enabled() && user.admin() && !(updated.enabled() && updated.admin())
                && !jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM auth_user WHERE enabled AND admin AND id <> ?)
                    """, Boolean.class, userId)) {
            return new UserUpdateResult(UserUpdateOutcome.LAST_ADMIN, null);
        }
        jdbc.update("UPDATE auth_user SET enabled = ?, admin = ? WHERE id = ?",
                updated.enabled(), updated.admin(), userId);
        return new UserUpdateResult(UserUpdateOutcome.UPDATED, updated);
    }

    @Override
    public Optional<Credentials> findUserByEmail(String email) {
        return jdbc.query("""
                SELECT id, email, password_hash, enabled FROM auth_user WHERE email = ?
                """, (rs, row) -> new Credentials(rs.getLong("id"), rs.getString("email"),
                rs.getString("password_hash"), rs.getBoolean("enabled")), email)
                .stream().findFirst();
    }

    public void storeToken(String tokenHash, long userId, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO auth_access_token (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, tokenHash, userId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public Optional<UserIdentity> findUsableToken(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT u.id, u.email, u.enabled, u.admin FROM auth_access_token t
                JOIN auth_user u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.expires_at > ? AND u.enabled
                """, (rs, row) -> new UserIdentity(rs.getLong("id"), rs.getString("email"),
                rs.getBoolean("enabled"), rs.getBoolean("admin")),
                tokenHash, Timestamp.from(now)).stream().findFirst();
    }

    public enum UserUpdateOutcome { UPDATED, NOT_FOUND, LAST_ADMIN }

    public record UserUpdateResult(UserUpdateOutcome outcome, UserIdentity user) { }
}
