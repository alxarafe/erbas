package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import com.alxarafe.erbas.assets.AssetsModule;
import com.alxarafe.erbas.assets.infrastructure.JdbcAssetAdapter;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.resources.http.ResourceController;
import com.alxarafe.erbas.navigation.http.NavigationController;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.assets.AssetResource.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AssetsIntegrationIT.Grants.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetsIntegrationIT {
    private static final String RECORDS = ResourceController.RECORDS + "/" + RESOURCE;
    private static final String ACTIONS = ResourceController.ACTIONS + "/" + RESOURCE;
    private static final String READER = "asset-reader@example.test", MAINTAINER = "asset-maintainer@example.test",
            SENSITIVE_USER = "asset-sensitive@example.test", DENIED = "asset-denied@example.test";
    private static final String PASSWORD = "assets-integration-fixture";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcAuthenticationStore accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ModuleStateService modules;
    @Autowired JdbcAssetAdapter adapter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Map<String, String> tokens = new HashMap<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<UUID> owned = new ArrayList<>();

    @BeforeAll void prepare() throws Exception {
        modules.enable(AssetsModule.ID);
        for (String email : List.of(READER, MAINTAINER, SENSITIVE_USER, DENIED)) {
            userIds.add(accounts.createUser(email, passwords.encode(PASSWORD), true, false));
            var response = request("POST", "/api/auth/login", null, Map.of("email", email, "password", PASSWORD));
            tokens.put(email, checked(response, HttpStatus.OK).get("accessToken").asString());
        }
    }
    @AfterAll void cleanup() {
        modules.enable(AssetsModule.ID);
        for (UUID id : owned) jdbc.update("DELETE FROM assets_assets WHERE id = ?", id);
        for (long id : userIds) {
            jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
        }
        http.close();
    }
    @Test void crudRoundTripUsesPostgresAndGenericPatchSemantics() throws Exception {
        var created = create(); var id = created.get(ID).asString();
        assertThat(created.get(LIFECYCLE).asString()).isEqualTo("DRAFT");
        assertThat(created.get(CREATED)).isEqualTo(created.get(UPDATED));
        assertThat(jdbc.queryForObject("SELECT name FROM assets_assets WHERE id = ?", String.class, UUID.fromString(id)))
                .isEqualTo(created.get(NAME).asString());
        assertThat(adapter.get(null, UUID.fromString(id))).isPresent();
        var fetched = checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.OK);
        assertThat(fetched).isEqualTo(created);
        var list = checked(call("GET", RECORDS, READER, null), HttpStatus.OK);
        assertThat(list.get("items").toString()).contains(id);
        var changed = checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, Map.of(NAME, "Changed", DESCRIPTION, "Details")), HttpStatus.OK);
        assertThat(changed.get(UPDATED).asString()).isNotEqualTo(created.get(UPDATED).asString());
        var clear = new LinkedHashMap<String, Object>(); clear.put(DESCRIPTION, null);
        changed = checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, clear), HttpStatus.OK);
        assertThat(changed.get(DESCRIPTION).isNull()).isTrue(); assertThat(changed.get(NAME).asString()).isEqualTo("Changed");
        assertThat(jdbc.queryForObject("SELECT name FROM assets_assets WHERE id = ?", String.class, UUID.fromString(id))).isEqualTo("Changed");
        checked(call("DELETE", RECORDS + "/" + id, MAINTAINER, null), HttpStatus.NO_CONTENT);
        checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.NOT_FOUND);
    }
    @ParameterizedTest @ValueSource(strings = {"id", "lifecycle", "createdAt", "updatedAt"})
    void serverOwnedFieldsAreInvalidOnCreateAndPatch(String field) throws Exception {
        var values = Map.<String, Object>of(ID, UUID.randomUUID().toString(), LIFECYCLE, "DRAFT", CREATED, "2026-01-01T00:00:00Z", UPDATED, "2026-01-01T00:00:00Z");
        var body = new HashMap<String, Object>(Map.of(CODE, UUID.randomUUID().toString(), NAME, "Test")); body.put(field, values.get(field));
        checked(call("POST", RECORDS, MAINTAINER, body), HttpStatus.BAD_REQUEST);
        var id = create().get(ID).asString();
        checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, Map.of(field, values.get(field))), HttpStatus.BAD_REQUEST);
    }
    @Test void missingFieldsTypesImmutabilityAndDuplicateCodeAreSafe() throws Exception {
        for (var body : List.of(Map.of(NAME, "Missing code"), Map.of(CODE, "Missing-name"), Map.of(CODE, 17, NAME, "Bad"), Map.of(CODE, "Bad-name", NAME, true)))
            checked(call("POST", RECORDS, MAINTAINER, body), HttpStatus.BAD_REQUEST);
        var created = create(); var id = created.get(ID).asString(); var code = created.get(CODE).asString();
        var duplicate = checked(call("POST", RECORDS, MAINTAINER, Map.of(CODE, code, NAME, "Duplicate")), HttpStatus.CONFLICT);
        assertThat(duplicate.get("code").asString()).isEqualTo(ResourceFailure.Code.resource_conflict.name());
        checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, Map.of(CODE, code)), HttpStatus.CONFLICT);
        checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, Map.of(CODE, 17)), HttpStatus.BAD_REQUEST);
    }
    @Test void lifecycleTransitionsAndConflictsAreAtomicAndPersistent() throws Exception {
        var created = create(); String id = created.get(ID).asString();
        action(id, RETIRE, SENSITIVE_USER, HttpStatus.CONFLICT);
        action(id, COMPLETE, MAINTAINER, HttpStatus.NO_CONTENT);
        var ready = checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.OK);
        assertThat(ready.get(LIFECYCLE).asString()).isEqualTo("READY");
        assertThat(ready.get(UPDATED)).isNotEqualTo(created.get(UPDATED));
        action(id, COMPLETE, MAINTAINER, HttpStatus.CONFLICT);
        checked(call("DELETE", RECORDS + "/" + id, MAINTAINER, null), HttpStatus.CONFLICT);
        action(id, RETIRE, SENSITIVE_USER, HttpStatus.NO_CONTENT);
        var retired = checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.OK);
        assertThat(retired.get(LIFECYCLE).asString()).isEqualTo("RETIRED");
        assertThat(retired.get(UPDATED)).isNotEqualTo(ready.get(UPDATED));
        action(id, COMPLETE, MAINTAINER, HttpStatus.CONFLICT); action(id, RETIRE, SENSITIVE_USER, HttpStatus.CONFLICT);
        checked(call("PATCH", RECORDS + "/" + id, MAINTAINER, Map.of(NAME, "Forbidden")), HttpStatus.CONFLICT);
        checked(call("DELETE", RECORDS + "/" + id, MAINTAINER, null), HttpStatus.CONFLICT);
        assertThat(checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.OK)).isEqualTo(retired);
    }
    @Test void effectiveProfilesEnforceIndependentReadWriteAndActionPermissions() throws Exception {
        var id = create().get(ID).asString();
        for (String who : List.of(READER, MAINTAINER, SENSITIVE_USER)) checked(call("GET", RECORDS + "/" + id, who, null), HttpStatus.OK);
        checked(call("GET", RECORDS, DENIED, null), HttpStatus.FORBIDDEN);
        checked(call("GET", RECORDS + "/" + id, DENIED, null), HttpStatus.FORBIDDEN);
        for (String who : List.of(READER, DENIED)) {
            checked(call("POST", RECORDS, who, Map.of(CODE, UUID.randomUUID().toString(), NAME, "Denied")), HttpStatus.FORBIDDEN);
            checked(call("PATCH", RECORDS + "/" + id, who, Map.of(NAME, "Denied")), HttpStatus.FORBIDDEN);
            checked(call("DELETE", RECORDS + "/" + id, who, null), HttpStatus.FORBIDDEN);
            action(id, COMPLETE, who, HttpStatus.FORBIDDEN); action(id, RETIRE, who, HttpStatus.FORBIDDEN);
        }
        action(id, COMPLETE, MAINTAINER, HttpStatus.NO_CONTENT);
        action(id, RETIRE, MAINTAINER, HttpStatus.FORBIDDEN);
        action(id, RETIRE, SENSITIVE_USER, HttpStatus.NO_CONTENT);
    }
    @Test void moduleStatePreservesDataAndFiltersNavigation() throws Exception {
        var id = create().get(ID).asString();
        modules.disable(AssetsModule.ID);
        try {
            var failure = checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failure.get("code").asString()).isEqualTo(ResourceFailure.Code.module_disabled.name());
            assertThat(checked(call("GET", NavigationController.PATH, READER, null), HttpStatus.OK).toString()).doesNotContain(RESOURCE);
        } finally { modules.enable(AssetsModule.ID); }
        checked(call("GET", RECORDS + "/" + id, READER, null), HttpStatus.OK);
    }
    @Test void discoveryPresentationAndLocalizedNavigationDoNotGrantAuthority() throws Exception {
        assertThat(checked(call("GET", ResourceMetadata.ROOT, DENIED, null), HttpStatus.OK).toString()).contains(RESOURCE);
        var presentation = checked(call("GET", ResourceMetadata.ROOT + "/" + RESOURCE + ResourceController.PRESENTATION_SUFFIX, DENIED, null), HttpStatus.OK);
        assertThat(presentation.get("presentation").has("create")).isTrue();
        var navigation = checked(call("GET", NavigationController.PATH, READER, null), HttpStatus.OK);
        assertThat(navigation.toString()).contains("RESOURCE", RESOURCE, "Assets");
        var spanish = HttpRequest.newBuilder(URI.create("http://localhost:" + port + NavigationController.PATH))
                .timeout(TIMEOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(READER))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "es-ES").GET().build();
        assertThat(checked(http.send(spanish, HttpResponse.BodyHandlers.ofString()), HttpStatus.OK).toString()).contains("Activos", RESOURCE);
        assertThat(checked(call("GET", NavigationController.PATH, DENIED, null), HttpStatus.OK).toString()).doesNotContain(RESOURCE);
        checked(call("GET", RECORDS, DENIED, null), HttpStatus.FORBIDDEN);
    }
    @Test void codeIsCaseSensitiveAndStoredWithoutTrimming() throws Exception {
        String base = " Asset-" + UUID.randomUUID();
        for (String code : List.of(base + " ", base.toLowerCase(Locale.ROOT) + " ")) {
            var row = checked(call("POST", RECORDS, MAINTAINER, Map.of(CODE, code, NAME, "Exact code")), HttpStatus.CREATED);
            UUID id = UUID.fromString(row.get(ID).asString()); owned.add(id);
            assertThat(row.get(CODE).asString()).isEqualTo(code);
            assertThat(jdbc.queryForObject("SELECT code FROM assets_assets WHERE id = ?", String.class, id)).isEqualTo(code);
        }
    }
    @Test void realSqlFilteringSortingAndSearchMatchAdvertisedCapabilities() throws Exception {
        var created = create(); String code = created.get(CODE).asString();
        var filter = Map.of("field", CODE, "operator", "eq", "value", code);
        String query = "?filter=" + ResourceMetadata.segment(ResourceJson.JSON.writeValueAsString(filter)) + "&sort=-updatedAt&search=" + code;
        var page = checked(call("GET", RECORDS + query, READER, null), HttpStatus.OK);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0)).isEqualTo(created);
        for (String operator : List.of("eq", "ne", "lt", "lte", "gt", "gte", "contains", "startsWith")) {
            var raw = ResourceQuery.transport(Map.of("filter", new String[] {ResourceJson.JSON.writeValueAsString(Map.of("field", CODE, "operator", operator, "value", code))}));
            var queryObject = ResourceQuery.validate(com.alxarafe.erbas.assets.AssetResource.definition(), raw);
            var pageResult = adapter.list(null, queryObject);
            assertThat(pageResult.items()).allMatch(queryObject::matches);
        }
    }
    private JsonNode create() throws Exception {
        var response = call("POST", RECORDS, MAINTAINER, Map.of(CODE, UUID.randomUUID().toString(), NAME, "Portátil de mantenimiento"));
        var row = checked(response, HttpStatus.CREATED); String id = row.get(ID).asString(); owned.add(UUID.fromString(id));
        assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).contains(RECORDS + "/" + id); return row;
    }
    private void action(String id, String action, String who, HttpStatus status) throws Exception {
        var result = checked(call("POST", ACTIONS + "/" + action + "/execute", who, Map.of("target", Map.of(ID, id))), status);
        if (status == HttpStatus.CONFLICT) assertThat(result.get("code").asString()).isEqualTo(ResourceFailure.Code.resource_action_conflict.name());
    }
    private HttpResponse<String> call(String method, String path, String who, Object body) throws Exception { return request(method, path, tokens.get(who), body); }
    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(TIMEOUT).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(ResourceJson.JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode checked(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status.value());
        if (status == HttpStatus.NO_CONTENT) { assertThat(response.body()).isEmpty(); return null; }
        var node = ResourceJson.JSON.readTree(response.body());
        if (status.isError()) {
            assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("code", "errors");
            assertThat(response.body()).doesNotContain("SQL", "Exception", "assets_assets", "constraint", "23505");
        }
        return node;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Grants {
        @Bean FunctionalPermissionPolicy assetsFixturePermissions() {
            return (user, permission) -> user != null && user.enabled() && switch (user.email()) {
                case READER -> permission.equals(READ);
                case MAINTAINER -> Set.of(READ, MAINTAIN).contains(permission);
                case SENSITIVE_USER -> Set.of(READ, SENSITIVE).contains(permission);
                default -> false;
            };
        }
    }
}
