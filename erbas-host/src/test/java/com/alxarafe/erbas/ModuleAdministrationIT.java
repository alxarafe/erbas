package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.modularity.persistence.JdbcModuleStateStore;
import com.alxarafe.erbas.wms.WmsModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static com.alxarafe.erbas.modularity.ConformanceModulesConfiguration.MATH_ID;
import static com.alxarafe.erbas.modularity.ConformanceModulesConfiguration.STATISTICS_ID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ConformanceModulesConfiguration.ENABLED_PROPERTY + "=true")
@ActiveProfiles(ConformanceModulesConfiguration.VALIDATION_PROFILE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleAdministrationIT {
    private static final String ADMIN_EMAIL = "module-administration-admin@example.test";
    private static final String USER_EMAIL = "module-administration-user@example.test";
    private static final String PASSWORD = "module-administration-fixture-password";
    private static final String RETIRED = "module-administration-retired";
    private static final String UNKNOWN = "module-administration-unknown";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String BEARER_SCHEME = "Bearer";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    @Value("${local.server.port}") private int port;
    @Autowired private ModuleRegistry registry;
    @Autowired private ModuleRuntime runtime;
    @Autowired private ModuleStateService states;
    @Autowired private ModuleStateStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private PasswordEncoder encoder;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final JsonMapper json = new JsonMapper();
    private long adminId;
    private long userId;
    private String adminToken;
    private String userToken;

    @BeforeAll
    void startupUsesNormalSynchronizationBeforeAnyHttpMutation() throws Exception {
        assertThat(registry.modules().stream().map(ErbasModule::descriptor)).containsExactly(
                new ModuleDescriptor(MATH_ID, ModuleApi.CURRENT_VERSION, List.of()),
                new ModuleDescriptor(STATISTICS_ID, ModuleApi.CURRENT_VERSION, List.of(MATH_ID)),
                new ModuleDescriptor(WmsModule.ID, ModuleApi.CURRENT_VERSION, List.of()));
        assertThat(runtime.modules()).containsExactlyElementsOf(registry.modules());
        assertThat(store.find(MATH_ID)).contains(ModuleStateService.INITIAL_ENABLED);
        assertThat(store.find(STATISTICS_ID)).contains(ModuleStateService.INITIAL_ENABLED);
        adminId = accounts.createUser(ADMIN_EMAIL, encoder.encode(PASSWORD), true, true);
        userId = accounts.createUser(USER_EMAIL, encoder.encode(PASSWORD), true, false);
        adminToken = login(ADMIN_EMAIL);
        userToken = login(USER_EMAIL);
        store.update(snapshot -> snapshot.put(RETIRED, false));
    }

    @BeforeEach
    void restoreThroughTheNormalAuthority() {
        states.enable(MATH_ID);
        states.enable(STATISTICS_ID);
        states.enable(WmsModule.ID);
    }

    @AfterAll
    void removeOnlyOwnedFixtures() {
        for (long id : new long[] {adminId, userId}) {
            if (id == 0) continue;
            jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM " + JdbcModuleStateStore.TABLE + " WHERE " + JdbcModuleStateStore.MODULE_ID
                + " IN (?, ?, ?)", MATH_ID, STATISTICS_ID, RETIRED);
        client.close();
    }

    @Test
    void canonicalGetHasExactDefaultsAndNeverExposesAnUnavailablePersistedRow() throws Exception {
        var response = get(ModuleController.MODULES_PATH, adminToken);
        assertJson(response, HttpStatus.OK);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of(
                "items", List.of(module(MATH_ID, true), module(STATISTICS_ID, true), module(WmsModule.ID, true)),
                "offset", 0, "limit", PagedCollection.DEFAULT_LIMIT, "total", 3,
                "order", List.of(Map.of("field", "id", "direction", "asc")))));
        assertThat(store.find(RETIRED)).contains(false);
    }

    @Test
    void realHttpPaginationPreservesCapacityTotalAndOrdering() throws Exception {
        var response = get(ModuleController.MODULES_PATH + "?offset=1&limit=1", adminToken);
        assertJson(response, HttpStatus.OK);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of(
                "items", List.of(module(STATISTICS_ID, true)), "offset", 1, "limit", 1, "total", 3,
                "order", List.of(Map.of("field", "id", "direction", "asc")))));
        var maximum = get(ModuleController.MODULES_PATH + "?limit=" + PagedCollection.MAXIMUM_LIMIT, adminToken);
        assertJson(maximum, HttpStatus.OK);
        assertThat(json.readTree(maximum.body()).get("limit").asInt()).isEqualTo(PagedCollection.MAXIMUM_LIMIT);
        for (String offset : List.of("3", "100", "9223372036854775808")) {
            var beyond = get(ModuleController.MODULES_PATH + "?offset=" + offset, adminToken);
            assertJson(beyond, HttpStatus.OK);
            assertThat(json.readTree(beyond.body()).get("items").isEmpty()).isTrue();
            assertThat(json.readTree(beyond.body()).get("offset").asString()).isEqualTo(offset);
        }
    }

    @Test
    void dependencyConflictsAreAtomicAndSuccessfulTransitionsAreIdempotent() throws Exception {
        var initial = snapshot();
        assertError(patch(MATH_ID, false, adminToken), HttpStatus.CONFLICT, ModuleController.DEPENDENCY_CONFLICT);
        assertThat(snapshot()).isEqualTo(initial);
        assertModule(patch(STATISTICS_ID, false, adminToken), STATISTICS_ID, false);
        assertModule(patch(STATISTICS_ID, false, adminToken), STATISTICS_ID, false);
        assertModule(patch(MATH_ID, false, adminToken), MATH_ID, false);
        assertModule(patch(MATH_ID, false, adminToken), MATH_ID, false);
        var disabled = snapshot();
        assertError(patch(STATISTICS_ID, true, adminToken), HttpStatus.CONFLICT, ModuleController.DEPENDENCY_CONFLICT);
        assertThat(snapshot()).isEqualTo(disabled);
        assertModule(patch(MATH_ID, true, adminToken), MATH_ID, true);
        assertModule(patch(MATH_ID, true, adminToken), MATH_ID, true);
        assertModule(patch(STATISTICS_ID, true, adminToken), STATISTICS_ID, true);
        assertThat(snapshot()).isEqualTo(initial);
    }

    @Test
    void coreAdministrationRemainsAccessibleWithEveryModuleDisabledAndReadsCurrentCommittedState() throws Exception {
        states.disable(STATISTICS_ID);
        states.disable(MATH_ID);
        states.disable(WmsModule.ID);
        var response = get(ModuleController.MODULES_PATH, adminToken);
        assertJson(response, HttpStatus.OK);
        assertThat(json.readTree(response.body()).get("items")).isEqualTo(
                json.valueToTree(List.of(module(MATH_ID, false), module(STATISTICS_ID, false), module(WmsModule.ID, false))));
        assertModule(patch(MATH_ID, true, adminToken), MATH_ID, true);
        assertModule(patch(STATISTICS_ID, true, adminToken), STATISTICS_ID, true);
        assertModule(patch(WmsModule.ID, true, adminToken), WmsModule.ID, true);
    }

    @Test
    void lookupIsCaseSensitiveAndHistoricalStateDoesNotMakeAnIdAvailable() throws Exception {
        for (String id : List.of(UNKNOWN, RETIRED, MATH_ID.toUpperCase(Locale.ROOT))) {
            assertError(patch(id, true, adminToken), HttpStatus.NOT_FOUND, ModuleController.NOT_FOUND);
        }
        assertThat(store.find(RETIRED)).contains(false);
    }

    @Test
    void realSecurityRejectsInvalidRequestsBeforeBodyAndLookup() throws Exception {
        var initial = snapshot();
        for (String token : new String[] {null, "invalid-fixture", userToken}) {
            var status = token != null && token.equals(userToken) ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
            var code = status == HttpStatus.FORBIDDEN ? "forbidden" : "unauthorized";
            assertError(get(ModuleController.MODULES_PATH + "?limit=invalid", token), status, code);
            assertError(send(HttpRequest.newBuilder(uri(ModuleController.MODULES_PATH + "/" + UNKNOWN))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method(HttpMethod.PATCH.name(), HttpRequest.BodyPublishers.ofString("{")), token), status, code);
            assertError(patch(MATH_ID, false, token), status, code);
        }
        assertThat(snapshot()).isEqualTo(initial);
    }

    @Test
    void invalidHttpBodyAndPaginationHaveExact400AndLeavePersistentStateUntouched() throws Exception {
        var initial = snapshot();
        for (String body : List.of("{", "[]", "{}", "{\"enabled\":null}", "{\"enabled\":false,\"extra\":true}")) {
            assertError(send(HttpRequest.newBuilder(uri(ModuleController.MODULES_PATH + "/" + MATH_ID))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method(HttpMethod.PATCH.name(), HttpRequest.BodyPublishers.ofString(body)), adminToken),
                    HttpStatus.BAD_REQUEST, ModuleController.INVALID_REQUEST);
        }
        assertError(get(ModuleController.MODULES_PATH + "?limit=" + (PagedCollection.MAXIMUM_LIMIT + 1), adminToken),
                HttpStatus.BAD_REQUEST, ModuleController.INVALID_REQUEST);
        assertError(get(ModuleController.MODULES_PATH + "?offset=-1", adminToken),
                HttpStatus.BAD_REQUEST, ModuleController.INVALID_REQUEST);
        assertThat(snapshot()).isEqualTo(initial);
    }

    private Map<String, Boolean> snapshot() {
        return Map.of(MATH_ID, store.find(MATH_ID).orElseThrow(), STATISTICS_ID, store.find(STATISTICS_ID).orElseThrow());
    }
    private Map<String, Object> module(String id, boolean enabled) {
        return Map.of("id", id, "moduleApiVersion", ModuleApi.CURRENT_VERSION, "dependencies",
                id.equals(STATISTICS_ID) ? List.of(MATH_ID) : List.of(), ModuleController.ENABLED_FIELD, enabled);
    }
    private void assertModule(HttpResponse<String> response, String id, boolean enabled) {
        assertJson(response, HttpStatus.OK);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(module(id, enabled)));
    }
    private void assertError(HttpResponse<String> response, HttpStatus status, String code) {
        assertJson(response, status);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of("code", code, "errors", java.util.List.of(Map.of("code", code)))));
    }
    private void assertJson(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).isEqualTo(status.value());
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains(CacheControl.noStore().getHeaderValue());
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains(BEARER_SCHEME);
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        request.timeout(REQUEST_TIMEOUT).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + " " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }
    private HttpResponse<String> patch(String id, boolean enabled, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(ModuleController.MODULES_PATH + "/" + id))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method(HttpMethod.PATCH.name(), HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(ModuleController.ENABLED_FIELD, enabled)))), token);
    }
    private String login(String email) throws Exception {
        var response = send(HttpRequest.newBuilder(uri(LOGIN_PATH)).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))), null);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        return json.readTree(response.body()).get("accessToken").asString();
    }
}
