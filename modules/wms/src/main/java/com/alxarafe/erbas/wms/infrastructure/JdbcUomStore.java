package com.alxarafe.erbas.wms.infrastructure;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.application.UomCatalogue;
import com.alxarafe.erbas.wms.domain.Uom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcUomStore implements UomCatalogue.Store {
    public static final String TABLE = "wms_uoms";
    private final JdbcTemplate jdbc;

    public JdbcUomStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insertIfCodeAvailable(Uom uom) {
        return jdbc.update("INSERT INTO " + TABLE + " (id, code, description) VALUES (?, ?, ?) "
                + "ON CONFLICT (code) DO NOTHING", uom.id(), uom.code(), uom.description()) == 1;
    }

    /** Count and page share one database snapshot; huge offsets never overflow JDBC. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UomCatalogue.Window list(BigInteger offset, int limit) {
        long total = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        var items = offset.compareTo(BigInteger.valueOf(total)) >= 0 ? List.<Uom>of()
                : jdbc.query("SELECT id, code, description FROM " + TABLE + " ORDER BY code ASC LIMIT ? OFFSET ?",
                        (rs, row) -> new Uom(rs.getObject("id", UUID.class), rs.getString("code"),
                                rs.getString("description")), limit, offset.longValueExact());
        return new UomCatalogue.Window(items, total);
    }
}
