package com.alxarafe.erbas.modularity.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import com.alxarafe.erbas.modularity.ModuleStateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcModuleStateStore implements ModuleStateStore {
    public static final String TABLE = "module_states";
    public static final String MODULE_ID = "module_id";
    public static final String ENABLED = "enabled";
    private static final String READ_COMMITTED = "read committed";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcModuleStateStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<Boolean> find(String moduleId) {
        return jdbc.query("SELECT " + ENABLED + " FROM " + TABLE + " WHERE " + MODULE_ID + " = ?",
                (rs, row) -> rs.getBoolean(ENABLED), moduleId).stream().findFirst();
    }

    @Override
    public void update(Consumer<Map<String, Boolean>> change) {
        transaction.executeWithoutResult(status -> {
            if (!READ_COMMITTED.equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
                throw new IllegalStateException("Module state updates require read committed isolation");
            }
            // Lock before reading, including when the table is empty or registries differ.
            jdbc.execute("LOCK TABLE " + TABLE + " IN SHARE ROW EXCLUSIVE MODE");
            Map<String, Boolean> before = new TreeMap<>();
            jdbc.query("SELECT " + MODULE_ID + ", " + ENABLED + " FROM " + TABLE,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            before.put(rs.getString(MODULE_ID), rs.getBoolean(ENABLED)));
            Map<String, Boolean> after = new TreeMap<>(before);
            change.accept(after);
            for (var state : after.entrySet()) {
                if (!before.containsKey(state.getKey())) {
                    jdbc.update("INSERT INTO " + TABLE + " (" + MODULE_ID + ", " + ENABLED + ") VALUES (?, ?)",
                            state.getKey(), state.getValue());
                } else if (!state.getValue().equals(before.get(state.getKey()))) {
                    jdbc.update("UPDATE " + TABLE + " SET " + ENABLED + " = ? WHERE " + MODULE_ID + " = ?",
                            state.getValue(), state.getKey());
                }
            }
        });
    }
}
