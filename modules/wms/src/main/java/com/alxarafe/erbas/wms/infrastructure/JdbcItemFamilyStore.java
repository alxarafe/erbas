package com.alxarafe.erbas.wms.infrastructure;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.application.ItemFamilyCatalogue;
import com.alxarafe.erbas.wms.domain.ItemFamily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcItemFamilyStore implements ItemFamilyCatalogue.Store {
    public static final String TABLE = "wms_item_families";
    private final JdbcTemplate jdbc;

    public JdbcItemFamilyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insertIfCodeAvailable(ItemFamily family) {
        return jdbc.update("INSERT INTO " + TABLE + " (id, code, name) VALUES (?, ?, ?) "
                + "ON CONFLICT (code) DO NOTHING", family.id(), family.code(), family.name()) == 1;
    }

    /** Count and page share one database snapshot; huge offsets never overflow JDBC. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemFamilyCatalogue.Window list(BigInteger offset, int limit) {
        long total = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        var items = offset.compareTo(BigInteger.valueOf(total)) >= 0 ? List.<ItemFamily>of()
                : jdbc.query("SELECT id, code, name FROM " + TABLE + " ORDER BY code ASC LIMIT ? OFFSET ?",
                        (rs, row) -> new ItemFamily(rs.getObject("id", UUID.class), rs.getString("code"),
                                rs.getString("name")), limit, offset.longValueExact());
        return new ItemFamilyCatalogue.Window(items, total);
    }
}
