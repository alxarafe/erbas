package com.alxarafe.erbas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs with only the CORE artifact and a real, disposable PostgreSQL instance. */
class CoreMigrationIT {
    private static final String SCHEMA = "core_physical_boundary_it";
    private static final String LOCATION = "classpath:db/core";

    @Test
    void freshCoreArtifactCreatesOnlyPlatformSchema() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("ERBAS_DATASOURCE_URL"));
        dataSource.setUser(System.getenv("ERBAS_DATASOURCE_USERNAME"));
        dataSource.setPassword(System.getenv("ERBAS_DATASOURCE_PASSWORD"));
        dataSource.setCurrentSchema(SCHEMA);
        var jdbc = new JdbcTemplate(dataSource);
        var flyway = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations(LOCATION).load();
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                    String.class, SCHEMA)).containsExactlyInAnyOrder("erbas_persistence_marker", "auth_user",
                            "auth_access_token", "module_states", "flyway_schema_history");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
