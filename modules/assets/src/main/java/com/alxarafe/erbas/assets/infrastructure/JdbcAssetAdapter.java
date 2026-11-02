package com.alxarafe.erbas.assets.infrastructure;

import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.assets.domain.AssetLifecycle;
import com.alxarafe.erbas.resources.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static com.alxarafe.erbas.assets.AssetResource.*;
import static com.alxarafe.erbas.resources.ResourceFailure.Code.*;

/** Module-owned SQL and transaction boundary; no generic resource persistence. */
public final class JdbcAssetAdapter implements ResourceExecutionAdapter {
    private static final String TABLE = "assets_assets";
    private static final int NANOS_PER_POSTGRES_TICK = 1_000;
    private static final String COLUMNS = "id, code, name, description, serial_number, lifecycle, created_at, updated_at";
    private static final Map<String, String> QUERY_COLUMNS = Map.of(ID, "id", CODE, "code COLLATE \"C\"", NAME, "name COLLATE \"C\"",
            LIFECYCLE, "lifecycle", CREATED, "created_at", UPDATED, "updated_at");
    private static final Map<String, String> COMPARISONS = Map.of("eq", "=", "ne", "<>", "lt", "<", "lte", "<=", "gt", ">", "gte", ">=");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcAssetAdapter(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc = jdbc; this.transactions = transactions; }
    @Override public String resourceId() { return RESOURCE; }

    @Override public ResourcePage list(UserIdentity user, ResourceQuery query) {
        var clauses = new ArrayList<String>(); var args = new ArrayList<Object>();
        if (query.search() != null) {
            clauses.add("(strpos(code, ?) > 0 OR strpos(name, ?) > 0)"); args.add(query.search()); args.add(query.search());
        }
        for (var filter : query.filters()) {
            String column = Objects.requireNonNull(QUERY_COLUMNS.get(filter.field()));
            switch (filter.operator()) {
                case "contains" -> { clauses.add("strpos(" + column + ", ?) > 0"); args.add(filter.value()); }
                case "startsWith" -> { clauses.add("starts_with(" + column + ", ?)"); args.add(filter.value()); }
                default -> { clauses.add(column + " " + Objects.requireNonNull(COMPARISONS.get(filter.operator())) + " ?"); args.add(filter.value()); }
            }
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + TABLE + where, Long.class, args.toArray()));
        if (query.offset().compareTo(BigInteger.valueOf(total)) >= 0) return new ResourcePage(List.of(), query.offset(), query.limit(), BigInteger.valueOf(total));
        String order = query.sort().stream().map(sort -> Objects.requireNonNull(QUERY_COLUMNS.get(sort.field())) + (sort.descending() ? " DESC" : " ASC"))
                .collect(java.util.stream.Collectors.joining(", "));
        args.add(query.limit()); args.add(query.offset().longValueExact());
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", this::record, args.toArray());
        return new ResourcePage(rows, query.offset(), query.limit(), BigInteger.valueOf(total));
    }
    @Override public Optional<Map<String, Object>> get(UserIdentity user, Object id) { return find(id, false); }
    private Optional<Map<String, Object>> find(Object id, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::record, id).stream().findFirst();
    }
    @Override public Map<String, Object> create(UserIdentity user, Map<String, Object> input) {
        try {
            return transactions.execute(status -> {
                var id = UUID.randomUUID(); var now = Instant.now();
                jdbc.update("INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        id, input.get(CODE), input.get(NAME), input.get(DESCRIPTION), input.get(SERIAL), AssetLifecycle.DRAFT.name(), Timestamp.from(now), Timestamp.from(now));
                return find(id, false).orElseThrow();
            });
        } catch (DuplicateKeyException duplicate) { throw new ResourceFailure(resource_conflict); }
    }
    @Override public Map<String, Object> update(UserIdentity user, Object id, Map<String, Object> merged) {
        return transactions.execute(status -> {
            var current = find(id, true).orElseThrow(() -> new ResourceFailure(resource_not_found));
            if (!lifecycle(current).mutable() || !Objects.equals(current.get(UPDATED), merged.get(UPDATED))) throw new ResourceFailure(resource_conflict);
            jdbc.update("UPDATE " + TABLE + " SET name = ?, description = ?, serial_number = ?, updated_at = ? WHERE id = ?",
                    merged.get(NAME), merged.get(DESCRIPTION), merged.get(SERIAL), Timestamp.from(nextTimestamp(current)), id);
            return find(id, false).orElseThrow();
        });
    }
    @Override public boolean delete(UserIdentity user, Object id) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var current = find(id, true);
            if (current.isEmpty()) return false;
            if (!lifecycle(current.orElseThrow()).deletable()) throw new ResourceFailure(resource_conflict);
            return jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", id) == 1;
        }));
    }
    public void transition(Object id, AssetLifecycle expected, AssetLifecycle target) {
        transactions.executeWithoutResult(status -> {
            var current = find(id, true).orElseThrow(() -> new ResourceFailure(resource_not_found));
            if (lifecycle(current) != expected) throw new ResourceFailure(resource_action_conflict);
            jdbc.update("UPDATE " + TABLE + " SET lifecycle = ?, updated_at = ? WHERE id = ?", target.name(), Timestamp.from(nextTimestamp(current)), id);
        });
    }
    private static AssetLifecycle lifecycle(Map<String, Object> row) { return AssetLifecycle.valueOf((String) row.get(LIFECYCLE)); }
    private static Instant nextTimestamp(Map<String, Object> row) {
        var now = Instant.now(); var previous = (Instant) row.get(UPDATED);
        // PostgreSQL timestamps have microsecond precision. Always advance the stored value.
        return now.isAfter(previous.plusNanos(NANOS_PER_POSTGRES_TICK)) ? now : previous.plusNanos(NANOS_PER_POSTGRES_TICK);
    }
    private Map<String, Object> record(ResultSet rs, int index) throws SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put(ID, rs.getObject("id", UUID.class)); row.put(CODE, rs.getString("code")); row.put(NAME, rs.getString("name"));
        row.put(DESCRIPTION, rs.getString("description")); row.put(SERIAL, rs.getString("serial_number")); row.put(LIFECYCLE, rs.getString("lifecycle"));
        row.put(CREATED, rs.getTimestamp("created_at").toInstant()); row.put(UPDATED, rs.getTimestamp("updated_at").toInstant());
        return Collections.unmodifiableMap(row);
    }
}
