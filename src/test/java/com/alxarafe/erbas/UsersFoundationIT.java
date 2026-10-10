package com.alxarafe.erbas;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.auth.infrastructure.AuthenticationBootstrapConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore.UserUpdateOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@SpringBootTest
class UsersFoundationIT {
    private static final String HASH = "synthetic-encoded-persistence-fixture";
    @Autowired private JdbcAuthenticationStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PasswordEncoder encoder;

    @Test
    @Transactional
    void defaultAdminAndExplicitIdentityHaveNoCredentials() {
        long normal = jdbc.queryForObject("""
                INSERT INTO auth_user (email, password_hash, enabled) VALUES ('default@example.test', ?, true)
                RETURNING id
                """, Long.class, HASH);
        assertThat(store.findUserById(normal)).contains(new UserIdentity(normal, "default@example.test", true, false));
        long admin = store.createUser("admin@example.test", HASH, true, true);
        assertThat(store.findUserById(admin)).contains(new UserIdentity(admin, "admin@example.test", true, true));
        assertThat(store.findUserById(Long.MAX_VALUE)).isEmpty();
        assertThat(store.findUserById(admin).orElseThrow().toString()).doesNotContain(HASH, "admin@example.test");
    }

    @Test
    @Transactional
    void exactEmailConflictIsExplicitWithoutNormalizationOrTransactionFailure() {
        String email = " Case-sensitive@example.test ";
        var created = store.createUserIfEmailAvailable(email, HASH, true).orElseThrow();
        assertThat(created.enabled()).isTrue();
        assertThat(created.admin()).isTrue();
        assertThat(store.createUserIfEmailAvailable(email, "other-encoded-fixture", false)).isEmpty();
        assertThat(store.findUserById(created.userId())).contains(created);
        assertThat(store.createUserIfEmailAvailable(email.trim(), HASH, false)).isPresent();
        assertThat(store.createUserIfEmailAvailable(email.toLowerCase(java.util.Locale.ROOT), HASH, false)).isPresent();
    }

    @Test
    @Transactional
    void lastAdminCannotBeDisabledOrDemotedAndDisabledAdminsDoNotCount() {
        long id = store.createUser("last-admin@example.test", HASH, true, true);
        store.createUser("disabled-admin@example.test", HASH, false, true);
        for (var result : List.of(store.updateUserState(id, false, null), store.updateUserState(id, null, false),
                store.updateUserState(id, false, false))) {
            assertThat(result.outcome()).isEqualTo(UserUpdateOutcome.LAST_ADMIN);
            assertThat(result.user()).isNull();
        }
        assertThat(store.findUserById(id).orElseThrow().enabled()).isTrue();
        assertThat(store.findUserById(id).orElseThrow().admin()).isTrue();
    }

    @Test
    @Transactional
    void twoAdminsPermitStateChangesAndReturnUpdatedIdentity() {
        long first = store.createUser("first-admin@example.test", HASH, true, true);
        long second = store.createUser("second-admin@example.test", HASH, true, true);
        var disabled = store.updateUserState(first, false, null);
        assertThat(disabled.outcome()).isEqualTo(UserUpdateOutcome.UPDATED);
        assertThat(disabled.user()).isEqualTo(new UserIdentity(first, "first-admin@example.test", false, true));
        assertThat(store.updateUserState(second, null, false).outcome()).isEqualTo(UserUpdateOutcome.LAST_ADMIN);
        assertThat(store.updateUserState(first, true, null).outcome()).isEqualTo(UserUpdateOutcome.UPDATED);
        assertThat(store.updateUserState(second, null, false).user())
                .isEqualTo(new UserIdentity(second, "second-admin@example.test", true, false));
    }

    @Test
    @Transactional
    void unknownAndEmptyUpdateAreExplicit() {
        assertThat(store.updateUserState(Long.MAX_VALUE, true, null).outcome()).isEqualTo(UserUpdateOutcome.NOT_FOUND);
        assertThatThrownBy(() -> store.updateUserState(Long.MAX_VALUE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void v3PreservesExistingV2UsersAsNonAdmin() {
        // This schema belongs exclusively to this test in its fresh native database.
        String schema = "users_foundation_v2_upgrade";
        var builder = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        try {
            assertThat(builder.target("2").load().migrate().migrationsExecuted).isEqualTo(2);
            jdbc.update("INSERT INTO " + schema + ".auth_user (email, password_hash, enabled) VALUES (?, ?, true)",
                    "existing@example.test", HASH);
            assertThat(builder.target("3").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT admin FROM " + schema + ".auth_user", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT enabled FROM " + schema + ".auth_user", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT password_hash FROM " + schema + ".auth_user", String.class).equals(HASH))
                    .isTrue();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void joinedRepeatableReadCannotUseAStaleAdministratorSnapshot() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.execute(status -> store.updateUserState(Long.MAX_VALUE, false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User state updates require read committed isolation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "demote", "both", "mixed"})
    void concurrentChangesSerializeBeforeReadingRemainingAdmins(String change) throws Exception {
        long first = store.createUser("race-first@example.test", HASH, true, true);
        long second = store.createUser("race-second@example.test", HASH, true, true);
        var changed = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondPid = new java.util.concurrent.atomic.AtomicInteger();
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> transaction.execute(status -> {
                var result = store.updateUserState(first, change.equals("demote") ? null : false,
                        change.equals("disable") || change.equals("mixed") ? null : false);
                changed.countDown();
                await(releaseCommit);
                return result.outcome();
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var loser = executor.submit(() -> transaction.execute(status -> {
                secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                secondStarted.countDown();
                return store.updateUserState(second, change.equals("demote") || change.equals("mixed") ? null : false,
                        change.equals("disable") ? null : false).outcome();
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            do {
                blocked = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ?
                        AND relation = 'auth_user'::regclass AND NOT granted)
                        """, Boolean.class, secondPid.get());
            } while (!blocked && !loser.isDone() && System.nanoTime() < deadline);
            assertThat(blocked).as("second transaction must wait before checking administrator state").isTrue();
            releaseCommit.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS)).isEqualTo(UserUpdateOutcome.UPDATED);
            assertThat(loser.get(10, TimeUnit.SECONDS)).isEqualTo(UserUpdateOutcome.LAST_ADMIN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user WHERE enabled AND admin", Integer.class))
                    .isEqualTo(1);
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM auth_user WHERE id IN (?, ?)", first, second);
        }
    }

    @Test
    void concurrentBootstrapCreatesOneAdminWithoutOverwritingCredentials() throws Exception {
        String email = "concurrent-bootstrap@example.test";
        String password = "synthetic-bootstrap-password";
        var environment = new MockEnvironment();
        environment.setActiveProfiles("validation");
        var barrier = new CyclicBarrier(2);
        var synchronizedEncoder = spy(encoder);
        // Both runners have observed no account before either hashes/inserts it.
        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return encoder.encode(invocation.getArgument(0));
        }).when(synchronizedEncoder).encode(password);
        var runner = new AuthenticationBootstrapConfiguration()
                .authenticationBootstrap(store, synchronizedEncoder, environment, email, password, true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { runner.run(new DefaultApplicationArguments()); return true; });
            var second = executor.submit(() -> { runner.run(new DefaultApplicationArguments()); return true; });
            assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
            var user = store.findUserByEmail(email).orElseThrow();
            assertThat(store.findUserById(user.id()).orElseThrow().admin()).isTrue();
            assertThat(user.enabled()).isTrue();
            assertThat(encoder.matches(password, user.passwordHash())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user WHERE email = ?", Integer.class, email))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM auth_user WHERE email = ?", email);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Transaction barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Transaction barrier interrupted");
        }
    }
}
