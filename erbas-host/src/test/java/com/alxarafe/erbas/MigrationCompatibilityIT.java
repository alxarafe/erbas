package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.wms.http.ItemFamilyController;
import com.alxarafe.erbas.wms.http.FamilyAssociationController;
import com.alxarafe.erbas.wms.infrastructure.JdbcItemFamilyStore;
import com.alxarafe.erbas.wms.infrastructure.JdbcFamilyAssociationStore;
import com.alxarafe.erbas.wms.http.StorageAttributeController;
import com.alxarafe.erbas.wms.infrastructure.JdbcStorageAttributeStore;
import com.alxarafe.erbas.wms.infrastructure.JdbcUomStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String UPGRADE_SCHEMA_PREFIX = "host_legacy_boundary_it_";
    private static final String V070_LOCATION = "classpath:db/legacy-v070";
    private static final UUID STORAGE_ID = UUID.fromString("abcdefab-1234-1234-1234-123456789012");
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
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcUomStore.TABLE, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcStorageAttributeStore.TABLE, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcItemFamilyStore.TABLE, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcFamilyAssociationStore.TABLE, Integer.class)).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(context.getBean(ModuleStateService.class).isEnabled(WmsModule.ID)).isTrue();
            assertThat(get(client, context, HEALTH_PATH, null).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get(client, context, UomController.UOMS_PATH, null).statusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + FRESH_SCHEMA + " CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void historicalV1ThroughV5OrV6DataSurviveRealHostStartup(int priorVersion) throws Exception {
        String schema = UPGRADE_SCHEMA_PREFIX + priorVersion;
        var dataSource = dataSource(schema);
        var jdbc = new JdbcTemplate(dataSource);
        var legacy = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations(LEGACY_LOCATION, V070_LOCATION).target(Integer.toString(priorVersion)).load();
        try {
            // Published migration fixtures are immutable; only pending migrations may be appended.
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(priorVersion);
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
            if (priorVersion == 6) jdbc.update("INSERT INTO " + JdbcStorageAttributeStore.TABLE + " (id, code, name) VALUES (?, ?, ?)",
                    STORAGE_ID, "OLD_STORAGE", "\u00a0Preserved storage 📦\u2003");
            var oldAttributes = priorVersion == 6 ? jdbc.queryForList("SELECT * FROM " + JdbcStorageAttributeStore.TABLE) : List.<Map<String, Object>>of();
            Long storageOid = priorVersion == 6 ? jdbc.queryForObject("SELECT ?::regclass::oid", Long.class, JdbcStorageAttributeStore.TABLE) : null;
            var oid = jdbc.queryForObject("SELECT ?::regclass::oid", Long.class, JdbcUomStore.TABLE);
            var users = jdbc.queryForList("SELECT * FROM auth_user");
            var tokens = jdbc.queryForList("SELECT * FROM auth_access_token");
            var marker = jdbc.queryForList("SELECT * FROM erbas_persistence_marker");
            try (var context = start(schema); var client = client()) {
                var flyway = context.getBean(Flyway.class);
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IS NULL OR version::int <= ? ORDER BY installed_rank", priorVersion))
                        .isEqualTo(history);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success", Integer.class))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT * FROM " + JdbcStorageAttributeStore.TABLE)).isEqualTo(oldAttributes);
                if (storageOid != null) assertThat(jdbc.queryForObject("SELECT ?::regclass::oid", Long.class, JdbcStorageAttributeStore.TABLE)).isEqualTo(storageOid);
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
                var createAttribute = HttpRequest.newBuilder(uri(context, StorageAttributeController.STORAGE_ATTRIBUTES_PATH)).timeout(HTTP_TIMEOUT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                                StorageAttributeController.CODE_FIELD, "IS_FOOD", StorageAttributeController.NAME_FIELD, " Food ")))).build();
                var attribute = client.send(createAttribute, HttpResponse.BodyHandlers.ofString());
                assertThat(attribute.statusCode()).isEqualTo(HttpStatus.CREATED.value());
                var page = get(client, context, StorageAttributeController.STORAGE_ATTRIBUTES_PATH, token);
                assertThat(page.statusCode()).isEqualTo(HttpStatus.OK.value());
                assertThat(json.readTree(page.body()).get("items").get(0)).isEqualTo(json.readTree(attribute.body()));
                var createFamily = HttpRequest.newBuilder(uri(context, ItemFamilyController.ITEM_FAMILIES_PATH)).timeout(HTTP_TIMEOUT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(ItemFamilyController.CODE_FIELD, "FAMILY", ItemFamilyController.NAME_FIELD, "Family")))).build();
                var family = client.send(createFamily, HttpResponse.BodyHandlers.ofString());
                assertThat(family.statusCode()).isEqualTo(HttpStatus.CREATED.value());
                String relationPath = ItemFamilyController.ITEM_FAMILIES_PATH + "/" + json.readTree(family.body()).get("id").asString() + "/" + FamilyAssociationController.ASSOCIATION_SEGMENT;
                var createRelation = HttpRequest.newBuilder(uri(context, relationPath)).timeout(HTTP_TIMEOUT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(FamilyAssociationController.ATTRIBUTE_ID, json.readTree(attribute.body()).get("id").asString())))).build();
                assertThat(client.send(createRelation, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(HttpStatus.CREATED.value());
                assertThat(json.readTree(get(client, context, relationPath, token).body()).get("items").get(0)).isEqualTo(json.readTree(attribute.body()));
                assertThat(flyway.migrate().migrationsExecuted).isZero();
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
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
