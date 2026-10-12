package com.alxarafe.erbas;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.modularity.persistence.JdbcModuleStateStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ModuleStateIT {
    private static final String A = "module-state-it-A";
    private static final String B = "module-state-it-B";
    private static final String C = "module-state-it-C";
    private static final String RETIRED = "module-state-it-retired";
    private static final int WAIT_SECONDS = 10;
    private static final int SHUTDOWN_SECONDS = 20;
    private static final int POLL_MILLIS = 10;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;

    @AfterEach
    void removeOnlyOwnedFixtureStates() {
        jdbc.update("DELETE FROM " + JdbcModuleStateStore.TABLE + " WHERE " + JdbcModuleStateStore.MODULE_ID
                + " IN (?, ?, ?, ?)", A, B, C, RETIRED);
    }

    @Test
    void newAvailableStatesDefaultEnabledAndRetiredStatesArePreserved() {
        store().update(states -> states.put(RETIRED, false));
        var empty = service(List.of());
        empty.synchronize();
        assertThat(store().find(RETIRED)).contains(false);
        var states = service(chain());
        states.synchronize();
        assertThat(states.isEnabled(A)).isEqualTo(ModuleStateService.INITIAL_ENABLED);
        assertThat(states.isEnabled(B)).isTrue();
        states.requireEnabled(C);
        states.disable(C);
        states.disable(B);
        states.disable(A);
        var reconstructed = service(chain());
        reconstructed.synchronize();
        assertThat(reconstructed.isEnabled(A)).isFalse();
        assertThat(reconstructed.isEnabled(B)).isFalse();
        assertThat(reconstructed.isEnabled(C)).isFalse();
        assertThat(store().find(RETIRED)).contains(false);
        assertThatThrownBy(() -> reconstructed.enable(RETIRED)).hasMessageContaining(RETIRED);
        assertThatThrownBy(() -> reconstructed.disable(RETIRED)).hasMessageContaining(RETIRED);
        assertThatThrownBy(() -> reconstructed.isEnabled(RETIRED)).hasMessageContaining(RETIRED);
        assertThatThrownBy(() -> reconstructed.requireEnabled(RETIRED)).hasMessageContaining(RETIRED);
    }

    @Test
    void dependencyTransitionsAreImmediateIdempotentAndNeverCascade() {
        var states = service(chain());
        states.synchronize();
        assertThatThrownBy(() -> states.disable(A)).hasMessageContaining(A).hasMessageContaining(B);
        assertThatThrownBy(() -> states.disable(B)).hasMessageContaining(B).hasMessageContaining(C);
        states.disable(C);
        states.disable(B);
        states.disable(A);
        states.disable(A);
        assertThatThrownBy(() -> states.requireEnabled(A)).hasMessageContaining(A);
        assertThatThrownBy(() -> states.enable(B)).hasMessageContaining(B).hasMessageContaining(A);
        assertThatThrownBy(() -> states.enable(C)).hasMessageContaining(C).hasMessageContaining(B);
        assertThat(states.isEnabled(B)).isFalse();
        states.enable(A);
        states.enable(A);
        states.enable(B);
        states.enable(C);
        assertThat(service(chain()).isEnabled(C)).isTrue();
        states.requireEnabled(C);
    }

    @Test
    void synchronizationRollsBackNewRowsInsteadOfRepairingInvalidPersistedState() {
        store().update(states -> {
            states.put(A, false);
            states.put(B, true);
        });
        assertThatThrownBy(() -> service(chain()).synchronize()).hasMessageContaining(A).hasMessageContaining(B);
        assertThat(store().find(A)).contains(false);
        assertThat(store().find(B)).contains(true);
        assertThat(store().find(C)).isEmpty();
    }

    @Test
    void disabledModulesStillInitializeAndInvalidStatePreventsStartup() {
        store().update(states -> {
            states.put(A, false);
            states.put(B, false);
            states.put(C, false);
        });
        var calls = new ArrayList<String>();
        try (var context = context(chain(calls))) {
            context.refresh();
            assertThat(calls).containsExactly(A, B, C);
            assertThat(context.getBean(ModuleStateService.class).isEnabled(C)).isFalse();
        }
        store().update(states -> states.put(B, true));
        calls.clear();
        try (var context = context(chain(calls))) {
            assertThatThrownBy(context::refresh).hasMessageContaining(A).hasMessageContaining(B);
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void stateKeysAreCaseSensitiveAndDataIsNotRemovedByDisabling() {
        String lower = A.toLowerCase(java.util.Locale.ROOT);
        // The alternate key is owned exclusively by this test.
        try {
            var modules = List.<ErbasModule>of(new FakeModule(A, List.of(), new ArrayList<>()),
                    new FakeModule(lower, List.of(), new ArrayList<>()));
            var states = service(modules);
            states.synchronize();
            states.disable(A);
            assertThat(states.isEnabled(A)).isFalse();
            assertThat(states.isEnabled(lower)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM erbas_persistence_marker", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM " + JdbcModuleStateStore.TABLE + " WHERE " + JdbcModuleStateStore.MODULE_ID + " = ?", lower);
        }
    }

    @Test
    void v4UpgradesExistingSchemaWithoutTouchingApplicationData() {
        String schema = "module_state_v3_upgrade";
        var builder = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        try {
            assertThat(builder.target("3").load().migrate().migrationsExecuted).isEqualTo(3);
            jdbc.update("INSERT INTO " + schema + ".auth_user (email, password_hash, enabled) VALUES (?, ?, true)",
                    "existing@module-state.test", "synthetic-encoded-fixture");
            assertThat(builder.target("4").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(builder.target("4").load().migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT enabled FROM " + schema + ".auth_user", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".module_states", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void concurrentEnableAndDisableWaitOnRealPostgresLockAndPreserveInvariant() throws Exception {
        var states = service(chain());
        states.synchronize();
        states.disable(C);
        states.disable(B);
        var started = new CountDownLatch(2);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(SHUTDOWN_SECONDS);
        var executor = Executors.newFixedThreadPool(2);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var command = blocker.createStatement()) {
                command.execute("LOCK TABLE " + JdbcModuleStateStore.TABLE + " IN SHARE ROW EXCLUSIVE MODE");
            }
            var disable = executor.submit(() -> attempt(transaction, firstPid, started, () -> states.disable(A)));
            var enable = executor.submit(() -> attempt(transaction, secondPid, started, () -> service(chain()).enable(B)));
            try {
                assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid IN (?, ?) AND relation = ?::regclass AND NOT granted",
                            Integer.class, firstPid.get(), secondPid.get(), JdbcModuleStateStore.TABLE);
                    if (waiting == 2) break;
                    Thread.sleep(POLL_MILLIS);
                } while (System.nanoTime() < deadline);
                assertThat(waiting).isEqualTo(2);
            } finally {
                blocker.rollback();
            }
            assertThat(List.of(disable.get(WAIT_SECONDS, TimeUnit.SECONDS), enable.get(WAIT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(states.isEnabled(A) || !states.isEnabled(B)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean attempt(TransactionTemplate transaction, AtomicInteger pid, CountDownLatch started, Runnable change) {
        try {
            transaction.executeWithoutResult(status -> {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                started.countDown();
                change.run();
            });
            return true;
        } catch (IllegalStateException rejected) {
            return false;
        }
    }

    private JdbcModuleStateStore store() {
        return new JdbcModuleStateStore(jdbc, transactions);
    }

    private ModuleStateService service(List<ErbasModule> modules) {
        ModuleGraph.order(modules);
        return new ModuleStateService(new ModuleRegistry(List.of(() -> modules)), store());
    }

    private AnnotationConfigApplicationContext context(List<ErbasModule> modules) {
        var context = new AnnotationConfigApplicationContext();
        context.register(ModularityConfiguration.class);
        context.registerBean(ModuleSource.class, () -> () -> modules);
        context.registerBean(ModuleStateService.class, () ->
                new ModuleStateService(context.getBean(ModuleRegistry.class), store()));
        return context;
    }

    private List<ErbasModule> chain() {
        return chain(new ArrayList<>());
    }

    private List<ErbasModule> chain(List<String> calls) {
        return List.of(new FakeModule(C, List.of(B), calls), new FakeModule(B, List.of(A), calls),
                new FakeModule(A, List.of(), calls));
    }

    private record FakeModule(String id, List<String> dependencies, List<String> calls) implements ErbasModule {
        @Override public ModuleDescriptor descriptor() {
            return new ModuleDescriptor(id, ModuleApi.CURRENT_VERSION, dependencies);
        }
        @Override public void initialize() {
            calls.add(id);
        }
    }
}
