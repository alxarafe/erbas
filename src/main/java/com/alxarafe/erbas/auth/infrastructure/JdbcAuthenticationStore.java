package com.alxarafe.erbas.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence only: callers supply encoded passwords and SHA-256 token digests. */
@Repository
public class JdbcAuthenticationStore {

    private final JdbcTemplate jdbc;

    public JdbcAuthenticationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createUser(String email, String passwordHash, boolean enabled) {
        return jdbc.queryForObject("""
                INSERT INTO auth_user (email, password_hash, enabled)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, email, passwordHash, enabled);
    }

    public Optional<UserCredentials> findUserByEmail(String email) {
        return jdbc.query("""
                SELECT id, email, password_hash, enabled FROM auth_user WHERE email = ?
                """, (rs, row) -> new UserCredentials(rs.getLong("id"), rs.getString("email"),
                rs.getString("password_hash"), rs.getBoolean("enabled")), email)
                .stream().findFirst();
    }

    public void storeToken(String tokenHash, long userId, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO auth_access_token (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, tokenHash, userId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public Optional<TokenIdentity> findUsableToken(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT u.id, u.email FROM auth_access_token t
                JOIN auth_user u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.expires_at > ? AND u.enabled
                """, (rs, row) -> new TokenIdentity(rs.getLong("id"), rs.getString("email")),
                tokenHash, Timestamp.from(now)).stream().findFirst();
    }

    public record UserCredentials(long id, String email, String passwordHash, boolean enabled) {
        @Override
        public String toString() {
            return "UserCredentials[id=" + id + ", enabled=" + enabled + "]";
        }
    }

    public record TokenIdentity(long userId, String email) { }
}
