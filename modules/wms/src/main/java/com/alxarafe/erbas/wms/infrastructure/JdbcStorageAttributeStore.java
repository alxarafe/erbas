package com.alxarafe.erbas.wms.infrastructure;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcStorageAttributeStore implements StorageAttributeCatalogue.Store {
    public static final String TABLE = "wms_storage_attributes";
    private final JdbcTemplate jdbc;

    public JdbcStorageAttributeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insertIfCodeAvailable(StorageAttribute attribute) {
        return jdbc.update("INSERT INTO " + TABLE + " (id, code, name) VALUES (?, ?, ?) "
                + "ON CONFLICT (code) DO NOTHING", attribute.id(), attribute.code(), attribute.name()) == 1;
    }

    /** Count and page share one database snapshot; huge offsets never overflow JDBC. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StorageAttributeCatalogue.Window list(BigInteger offset, int limit) {
        long total = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        var items = offset.compareTo(BigInteger.valueOf(total)) >= 0 ? List.<StorageAttribute>of()
                : jdbc.query("SELECT id, code, name FROM " + TABLE + " ORDER BY code ASC LIMIT ? OFFSET ?",
                        (rs, row) -> new StorageAttribute(rs.getObject("id", UUID.class), rs.getString("code"),
                                rs.getString("name")), limit, offset.longValueExact());
        return new StorageAttributeCatalogue.Window(items, total);
    }
}
