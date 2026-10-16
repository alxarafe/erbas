package com.alxarafe.erbas;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.modularity.http.ModuleEndpointInterceptor;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.UomCatalogue;
import com.alxarafe.erbas.wms.domain.Uom;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.wms.infrastructure.JdbcUomStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WmsIntegrationIT {
    private static final String ADMIN_EMAIL = "wms-it-admin@example.test";
    private static final String USER_EMAIL = "wms-it-user@example.test";
    private static final String PASSWORD = "wms-it-fixture-password";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String BEARER = "Bearer";
    private static final String EA = "EA";
    private static final String BOX = "BOX";
    private static final String RACE = "RACE";
    private static final List<String> ORDER_CODES = List.of("A1", "A10", "A2", "B");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int WAIT_SECONDS = 10;
    @Value("${local.server.port}") private int port;
    @Autowired private UomCatalogue catalogue;
    @Autowired private JdbcUomStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ModuleRegistry registry;
    @Autowired private ModuleRuntime runtime;
    @Autowired private ModuleStateService states;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private PasswordEncoder encoder;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private long adminId;
    private long userId;
    private String admin;
    private String user;

    @BeforeAll
    void normalProductionCompositionAndRealBearers() throws Exception {
        assertThat(registry.modules().stream().map(ErbasModule::descriptor))
                .containsExactly(new ModuleDescriptor(WmsModule.ID, ModuleApi.CURRENT_VERSION, List.of()));
        assertThat(runtime.modules()).containsExactlyElementsOf(registry.modules());
        assertThat(states.isEnabled(WmsModule.ID)).isTrue();
        adminId = accounts.createUser(ADMIN_EMAIL, encoder.encode(PASSWORD), true, true);
        userId = accounts.createUser(USER_EMAIL, encoder.encode(PASSWORD), true, false);
        admin = login(ADMIN_EMAIL);
        user = login(USER_EMAIL);
    }

    @AfterEach
    void removeOnlyOwnedCatalogueFixturesAndRestoreState() {
        states.enable(WmsModule.ID);
        for (String code : List.of(EA, BOX, RACE)) deleteCode(code);
        ORDER_CODES.forEach(this::deleteCode);
    }

    @AfterAll
    void removeOnlyOwnedIdentities() {
        for (long id : new long[] {adminId, userId}) {
            if (id == 0) continue;
            jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
        }
        client.close();
    }

    @Test
    void v5UpgradesV4WithoutChangingCoreDataAndFreshHistoryHasAllMigrations() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
        String schema = "wms_it_v4_upgrade";
        var builder = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations(flyway.getConfiguration().getLocations());
        try {
            assertThat(builder.target("4").load().migrate().migrationsExecuted).isEqualTo(4);
            jdbc.update("INSERT INTO " + schema + ".module_states (module_id, enabled) VALUES (?, false)", WmsModule.ID);
            assertThat(builder.target("5").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(builder.target("5").load().migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT enabled FROM " + schema + ".module_states", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + JdbcUomStore.TABLE, Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, schema))
                    .contains("erbas_persistence_marker", "auth_user", "auth_access_token", "module_states", JdbcUomStore.TABLE);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void postgresPersistsNormalizedUnicodeAndPagesInOrdinalCodeOrder() {
        for (String code : ORDER_CODES.reversed()) catalogue.create(code, " " + code + " ");
        String description = "📦".repeat(Uom.DESCRIPTION_MAXIMUM);
        var created = catalogue.create(EA, " \t" + description + "\r\n ").uom();
        assertThat(jdbc.queryForObject("SELECT description FROM " + JdbcUomStore.TABLE + " WHERE id = ?",
                String.class, created.id())).isEqualTo(description);
        var first = catalogue.list(BigInteger.ZERO, 2);
        assertThat(first.total()).isEqualTo(ORDER_CODES.size() + 1);
        assertThat(first.items()).extracting(Uom::code).containsExactly("A1", "A10");
        assertThat(catalogue.list(BigInteger.ONE, 2).items()).extracting(Uom::code).containsExactly("A10", "A2");
        for (BigInteger offset : List.of(BigInteger.valueOf(first.total()), BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
            var window = catalogue.list(offset, PagedCollection.MAXIMUM_LIMIT);
            assertThat(window.items()).isEmpty();
            assertThat(window.total()).isEqualTo(first.total());
        }
    }

    @Test
    void databaseEnforcesIdentityRequiredColumnsAndUniqueCode() {
        var created = catalogue.create(EA, "Each").uom();
        assertThat(catalogue.create(EA, "Changed").outcome()).isEqualTo(UomCatalogue.CreateOutcome.CODE_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT description FROM " + JdbcUomStore.TABLE + " WHERE code = ?", String.class, EA))
                .isEqualTo(created.description());
        var transaction = new TransactionTemplate(transactions);
        for (Object[] values : List.of(new Object[] {null, BOX, "Box"}, new Object[] {UUID.randomUUID(), null, "Box"},
                new Object[] {UUID.randomUUID(), BOX, null}, new Object[] {created.id(), BOX, "Box"},
                new Object[] {UUID.randomUUID(), EA, "Box"})) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO " + JdbcUomStore.TABLE + " (id, code, description) VALUES (?, ?, ?)", values)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void simultaneousInsertsHaveOneWinnerAndOneConflictWithoutLosingOriginalState() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            Callable<UomCatalogue.CreateResult> insert = () -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return catalogue.create(RACE, " Race ");
            };
            var first = executor.submit(insert);
            var second = executor.submit(insert);
            assertThat(List.of(first.get(WAIT_SECONDS, TimeUnit.SECONDS).outcome(), second.get(WAIT_SECONDS, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder(UomCatalogue.CreateOutcome.CREATED, UomCatalogue.CreateOutcome.CODE_CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcUomStore.TABLE + " WHERE code = ?", Integer.class, RACE))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void httpCreationCommitsNormalizedContentAndDuplicateErrorsIgnoreLocale() throws Exception {
        String description = "\u00a0Standard   📦\u00a0";
        var response = create(json.writeValueAsString(Map.of(UomController.CODE_FIELD, EA,
                UomController.DESCRIPTION_FIELD, " \t" + description + "\r\n ")), admin);
        assertJson(response, HttpStatus.CREATED);
        var created = json.readTree(response.body());
        assertThat(created.size()).isEqualTo(3);
        assertThat(UUID.fromString(created.get("id").asString())).isNotEqualTo(new UUID(0, 0));
        assertThat(created.get(UomController.DESCRIPTION_FIELD).asString()).isEqualTo(description);
        assertThat(jdbc.queryForObject("SELECT description FROM " + JdbcUomStore.TABLE + " WHERE code = ?", String.class, EA))
                .isEqualTo(description);
        for (String locale : List.of("en", "es")) {
            assertError(send(HttpRequest.newBuilder(uri(UomController.UOMS_PATH)).header(HttpHeaders.ACCEPT_LANGUAGE, locale)
                    .POST(HttpRequest.BodyPublishers.ofString(body(EA, "Changed"))), admin),
                    HttpStatus.CONFLICT, UomController.CODE_CONFLICT);
        }
        var listing = send(HttpRequest.newBuilder(uri(UomController.UOMS_PATH)).header(HttpHeaders.ACCEPT_LANGUAGE, "es-ES").GET(), admin);
        assertJson(listing, HttpStatus.OK);
        var page = json.readTree(listing.body());
        assertThat(page.get("items").get(0)).isEqualTo(created);
        assertThat(page.get("limit").asInt()).isEqualTo(PagedCollection.DEFAULT_LIMIT);
        assertThat(page.get("order")).isEqualTo(json.readTree("[{\"field\":\"code\",\"direction\":\"asc\"}]"));
        assertThat(page.size()).isEqualTo(5);
    }

    @Test
    void realSecurityAndDynamicGatePrecedeInvalidBodyAndQueryAndRetainData() throws Exception {
        assertJson(create(body(EA, "Each"), admin), HttpStatus.CREATED);
        for (boolean enabled : List.of(true, false)) {
            transition(enabled);
            assertError(create("{", null), HttpStatus.UNAUTHORIZED, "unauthorized");
            assertError(create("{", "invalid-fixture"), HttpStatus.UNAUTHORIZED, "unauthorized");
            assertError(create("{", user), HttpStatus.FORBIDDEN, "forbidden");
            assertError(get("?offset=bad", null), HttpStatus.UNAUTHORIZED, "unauthorized");
            assertError(get("?offset=bad", user), HttpStatus.FORBIDDEN, "forbidden");
            if (!enabled) {
                assertError(create("{", admin), HttpStatus.SERVICE_UNAVAILABLE, ModuleEndpointInterceptor.DISABLED_CODE);
                assertError(create(body(BOX, "Box"), admin), HttpStatus.SERVICE_UNAVAILABLE, ModuleEndpointInterceptor.DISABLED_CODE);
                assertError(get("?offset=bad", admin), HttpStatus.SERVICE_UNAVAILABLE, ModuleEndpointInterceptor.DISABLED_CODE);
                assertError(get("", admin), HttpStatus.SERVICE_UNAVAILABLE, ModuleEndpointInterceptor.DISABLED_CODE);
            }
        }
        transition(true);
        assertJson(get("", admin), HttpStatus.OK);
        assertThat(json.readTree(get("", admin).body()).get("items").get(0).get(UomController.CODE_FIELD).asString()).isEqualTo(EA);
        assertJson(create(body(BOX, "Box"), admin), HttpStatus.CREATED);
        assertThat(catalogue.list(BigInteger.ZERO, PagedCollection.DEFAULT_LIMIT).total()).isEqualTo(2);
    }

    @Test
    void strictJsonRejectsMalformedDuplicateCoercedMissingAndUnknownInputsWithoutMutation() throws Exception {
        for (String input : List.of("", " ", "{", "[]", "true", "42", "\"scalar\"", "null", "{}",
                "{\"code\":\"EA\"}", "{\"description\":\"Each\"}", "{\"Code\":\"EA\",\"description\":\"Each\"}",
                "{\"code\":\"EA\",\"description\":\"Each\",\"extra\":true}",
                "{\"code\":\"EA\",\"code\":\"BOX\",\"description\":\"Each\"}",
                "{\"code\":\"EA\",\"description\":\"Each\",\"description\":\"Box\"}",
                "{\"code\":42,\"description\":\"Each\"}", "{\"code\":true,\"description\":\"Each\"}",
                "{\"code\":null,\"description\":\"Each\"}", "{\"code\":\"EA\",\"description\":42}",
                "{\"code\":\"EA\",\"description\":false}", "{\"code\":\"EA\",\"description\":null}",
                "{\"code\":\"EA\",\"description\":\"Each\"} {}",
                "{\"code\":\"EA\",\"description\":\"\\ud800\"}", "{\"code\":\"EA\",\"description\":\"\\u0000\"}")) {
            assertError(create(input, admin), HttpStatus.BAD_REQUEST, UomController.INVALID_REQUEST);
        }
        assertThat(catalogue.list(BigInteger.ZERO, 1).total()).isZero();
    }

    @Test
    void strictQueryValidationAndHttpPaginationReuseCollectionsPolicy() throws Exception {
        for (String query : List.of("?sort=code", "?limit=1&limit=2", "?offset=0&offset=1", "?offset=-1",
                "?offset=0.5", "?offset=1e2", "?offset=%200", "?offset=", "?limit=", "?limit=0", "?limit=" + (PagedCollection.MAXIMUM_LIMIT + 1))) {
            assertError(get(query, admin), HttpStatus.BAD_REQUEST, UomController.INVALID_REQUEST);
        }
        assertError(send(HttpRequest.newBuilder(uri(UomController.UOMS_PATH + "?unknown=1"))
                .POST(HttpRequest.BodyPublishers.ofString(body(EA, "Each"))), admin), HttpStatus.BAD_REQUEST, UomController.INVALID_REQUEST);
        for (String code : ORDER_CODES.reversed()) assertJson(create(body(code, code), admin), HttpStatus.CREATED);
        var pageResponse = get("?offset=1&limit=1", admin);
        assertJson(pageResponse, HttpStatus.OK);
        var page = json.readTree(pageResponse.body());
        assertThat(page.get("total").asInt()).isEqualTo(ORDER_CODES.size());
        assertThat(page.get("offset").asInt()).isEqualTo(1);
        assertThat(page.get("limit").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get(UomController.CODE_FIELD).asString()).isEqualTo("A10");
        for (String query : List.of("?offset=4&limit=100", "?offset=9223372036854775808")) {
            var beyond = get(query, admin);
            assertJson(beyond, HttpStatus.OK);
            assertThat(json.readTree(beyond.body()).get("items").isEmpty()).isTrue();
        }
    }

    private void deleteCode(String code) { jdbc.update("DELETE FROM " + JdbcUomStore.TABLE + " WHERE code = ?", code); }
    private String body(String code, String description) {
        return json.writeValueAsString(Map.of(UomController.CODE_FIELD, code, UomController.DESCRIPTION_FIELD, description));
    }
    private void transition(boolean enabled) throws Exception {
        assertJson(send(HttpRequest.newBuilder(uri(ModuleController.MODULES_PATH + "/" + WmsModule.ID))
                .method(HttpMethod.PATCH.name(), HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of(ModuleController.ENABLED_FIELD, enabled)))), admin), HttpStatus.OK);
    }
    private HttpResponse<String> create(String body, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(UomController.UOMS_PATH)).POST(HttpRequest.BodyPublishers.ofString(body)), token);
    }
    private HttpResponse<String> get(String query, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(UomController.UOMS_PATH + query)).GET(), token);
    }
    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        request.timeout(REQUEST_TIMEOUT).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, BEARER + " " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String login(String email) throws Exception {
        var response = send(HttpRequest.newBuilder(uri(LOGIN_PATH)).POST(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))), null);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        return json.readTree(response.body()).get("accessToken").asString();
    }
    private void assertJson(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).isEqualTo(status.value());
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains(CacheControl.noStore().getHeaderValue());
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains(BEARER);
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).isEmpty();
    }
    private void assertError(HttpResponse<String> response, HttpStatus status, String code) {
        assertJson(response, status);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of(UomController.CODE_FIELD, code)));
    }
}
