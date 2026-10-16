package com.alxarafe.erbas.wms.infrastructure;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.alxarafe.erbas.wms.application.FamilyAssociations;
import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcFamilyAssociationStore implements FamilyAssociations.Store {
    public static final String TABLE = "wms_item_family_storage_attributes";
    private final JdbcTemplate jdbc;

    public JdbcFamilyAssociationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public FamilyAssociations.Result mutate(UUID familyId, FamilyAssociations.Command command,
            Function<FamilyAssociations.Snapshot, FamilyAssociations.Result> evaluate) {
        // Lock the parent even when its relation is empty. Every writer for that family uses this lock.
        boolean exists = !jdbc.queryForList("SELECT id FROM " + JdbcItemFamilyStore.TABLE + " WHERE id = ? FOR UPDATE", UUID.class, familyId).isEmpty();
        var references = new HashSet<>(command.add());
        references.addAll(command.remove());
        var attributes = new HashMap<UUID, StorageAttribute>();
        for (UUID id : references) {
            jdbc.query("SELECT id, code, name FROM " + JdbcStorageAttributeStore.TABLE + " WHERE id = ?",
                    (rs, row) -> new StorageAttribute(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")), id)
                    .forEach(attribute -> attributes.put(attribute.id(), attribute));
        }
        var original = new HashSet<>(jdbc.queryForList("SELECT storage_attribute_id FROM " + TABLE + " WHERE item_family_id = ?", UUID.class, familyId));
        var result = evaluate.apply(new FamilyAssociations.Snapshot(exists, attributes, original));
        if (!result.issues().isEmpty()) return result;
        for (UUID id : original) if (!result.desired().contains(id)) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE item_family_id = ? AND storage_attribute_id = ?", familyId, id);
        }
        for (UUID id : result.desired()) if (!original.contains(id)) {
            jdbc.update("INSERT INTO " + TABLE + " (item_family_id, storage_attribute_id) VALUES (?, ?)", familyId, id);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyAssociations.Listing list(UUID familyId, BigInteger offset, int limit) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + JdbcItemFamilyStore.TABLE + " WHERE id = ?)", Boolean.class, familyId));
        if (!exists) return new FamilyAssociations.Listing(false, null);
        long total = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE item_family_id = ?", Long.class, familyId);
        var items = offset.compareTo(BigInteger.valueOf(total)) >= 0 ? List.<StorageAttribute>of() : jdbc.query(
                "SELECT a.id, a.code, a.name FROM " + JdbcStorageAttributeStore.TABLE + " a JOIN " + TABLE
                        + " r ON a.id = r.storage_attribute_id WHERE r.item_family_id = ? ORDER BY a.code ASC LIMIT ? OFFSET ?",
                (rs, row) -> new StorageAttribute(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")), familyId, limit, offset.longValueExact());
        return new FamilyAssociations.Listing(true, new StorageAttributeCatalogue.Window(items, total));
    }
}
