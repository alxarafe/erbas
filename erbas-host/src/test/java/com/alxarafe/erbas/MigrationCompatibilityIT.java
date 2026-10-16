package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.wms.infrastructure.JdbcUomStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCompatibilityIT {
    private static final String FRESH_SCHEMA = "host_fresh_boundary_it";
    private static final String UPGRADE_SCHEMA = "host_v060_boundary_it";
    private static final String LEGACY_LOCATION = "classpath:db/legacy-v060";
    private static final String HISTORY = "SELECT * FROM flyway_schema_history ORDER BY installed_rank";
    private static final String EMAIL = "preserved-v060@example.test";
    private static final String PASSWORD = "preserved-v060-password";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String HEALTH_PATH = "/health";
    private static final UUID UOM_ID = UUID.fromString("3b92a187-5de0-4e71-8eaf-456fd8c20216");
    private static final String UOM_CODE = "EA";
    private static final String UOM_DESCRIPTION = "Existing unit";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void freshCompositionMigratesBothOwnersAndStartsHttp() throws Exception {
        var dataSource = dataSource(FRESH_SCHEMA);
        var jdbc = new JdbcTemplate(dataSource);
        try (var context = start(FRESH_SCHEMA); var client = client()) {
            var flyway = context.getBean(Flyway.class);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcUomStore.TABLE, Integer.class)).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(context.getBean(ModuleStateService.class).isEnabled(WmsModule.ID)).isTrue();
            assertThat(get(client, context, HEALTH_PATH, null).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get(client, context, UomController.UOMS_PATH, null).statusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + FRESH_SCHEMA + " CASCADE");
        }
    }

    @Test
    void currentV1ThroughV5HistoryAndDataSurviveRealHostStartup() throws Exception {
        var dataSource = dataSource(UPGRADE_SCHEMA);
        var jdbc = new JdbcTemplate(dataSource);
        var legacy = Flyway.configure().dataSource(dataSource).schemas(UPGRADE_SCHEMA).defaultSchema(UPGRADE_SCHEMA)
                .locations(LEGACY_LOCATION).load();
        try {
            // These five SQL fixtures are byte-identical to the exact approved base.
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(5);
            var history = jdbc.queryForList(HISTORY);
            var hash = new AuthenticationConfiguration().passwordEncoder().encode(PASSWORD);
            Long userId = jdbc.queryForObject("INSERT INTO auth_user (email, password_hash, enabled, admin) VALUES (?, ?, true, true) RETURNING id",
                    Long.class, EMAIL, hash);
            String tokenHash = "a".repeat(64);
            jdbc.update("INSERT INTO auth_access_token (token_hash, user_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                    tokenHash, userId);
            jdbc.update("INSERT INTO module_states (module_id, enabled) VALUES (?, false)", WmsModule.ID);
            jdbc.update("INSERT INTO " + JdbcUomStore.TABLE + " (id, code, description) VALUES (?, ?, ?)",
                    UOM_ID, UOM_CODE, UOM_DESCRIPTION);
            var oid = jdbc.queryForObject("SELECT ?::regclass::oid", Long.class, JdbcUomStore.TABLE);
            var users = jdbc.queryForList("SELECT * FROM auth_user");
            var tokens = jdbc.queryForList("SELECT * FROM auth_access_token");
            var marker = jdbc.queryForList("SELECT * FROM erbas_persistence_marker");
            try (var context = start(UPGRADE_SCHEMA); var client = client()) {
                var flyway = context.getBean(Flyway.class);
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList(HISTORY)).isEqualTo(history);
                assertThat(jdbc.queryForObject("SELECT ?::regclass::oid", Long.class, JdbcUomStore.TABLE)).isEqualTo(oid);
                assertThat(jdbc.queryForList("SELECT * FROM auth_user")).isEqualTo(users);
                assertThat(jdbc.queryForList("SELECT * FROM auth_access_token")).isEqualTo(tokens);
                assertThat(jdbc.queryForList("SELECT * FROM erbas_persistence_marker")).isEqualTo(marker);
                var states = context.getBean(ModuleStateService.class);
                assertThat(states.isEnabled(WmsModule.ID)).isFalse();
                var json = new JsonMapper();
                var request = HttpRequest.newBuilder(uri(context, LOGIN_PATH)).timeout(HTTP_TIMEOUT)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)))).build();
                var login = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value());
                String token = json.readTree(login.body()).get("accessToken").asText();
                assertThat(get(client, context, UomController.UOMS_PATH, token).statusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
                states.enable(WmsModule.ID);
                var response = get(client, context, UomController.UOMS_PATH, token);
                assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
                var unit = json.readTree(response.body()).get("items").get(0);
                assertThat(unit.get("id").asText()).isEqualTo(UOM_ID.toString());
                assertThat(unit.get("code").asText()).isEqualTo(UOM_CODE);
                assertThat(unit.get("description").asText()).isEqualTo(UOM_DESCRIPTION);
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
        }
    }

    private static PGSimpleDataSource dataSource(String schema) {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("ERBAS_DATASOURCE_URL"));
        source.setUser(System.getenv("ERBAS_DATASOURCE_USERNAME"));
        source.setPassword(System.getenv("ERBAS_DATASOURCE_PASSWORD"));
        source.setCurrentSchema(schema);
        return source;
    }

    private static ConfigurableWebServerApplicationContext start(String schema) {
        // Arguments override the normal host defaults, exclusively inside the isolated native database.
        return (ConfigurableWebServerApplicationContext) new SpringApplicationBuilder(ErbasApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + System.getenv("ERBAS_DATASOURCE_URL") + "?currentSchema=" + schema,
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    private static URI uri(ConfigurableWebServerApplicationContext context, String path) {
        return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path);
    }

    private static HttpResponse<String> get(HttpClient client, ConfigurableWebServerApplicationContext context, String path, String token)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri(context, path)).timeout(HTTP_TIMEOUT);
        if (token != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
