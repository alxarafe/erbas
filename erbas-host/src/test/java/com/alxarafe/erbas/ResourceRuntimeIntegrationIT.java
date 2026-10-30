package com.alxarafe.erbas;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.resources.http.ResourceController;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import com.alxarafe.erbas.navigation.http.NavigationController;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(ConformanceModulesConfiguration.VALIDATION_PROFILE)
@TestPropertySource(properties = ConformanceModulesConfiguration.ENABLED_PROPERTY + "=true")
@Import(ResourceRuntimeIntegrationIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceRuntimeIntegrationIT {
    private static final String PASSWORD = "resource-integration-fixture-password";
    private static final String ADMIN_EMAIL = "resource-admin@example.test";
    private static final String NORMAL_EMAIL = "resource-normal@example.test";
    private static final String MARKERS = "test.markers";
    private static final String TEST_MODULE = "test.resources";
    private static final String MARKER_PERMISSION = "test.markers.read";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String RECORDS = ResourceController.RECORDS + "/" + ResourceConformanceConfiguration.RESOURCE;
    @Value("${local.server.port}") int port;
    @Autowired JdbcAuthenticationStore accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleStateService modules;
    @Autowired ResourceRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> records = new ArrayList<>();
    private long adminId, normalId;
    private String admin, normal;
    @BeforeAll void provision() throws Exception {
        adminId = accounts.createUser(ADMIN_EMAIL, encoder.encode(PASSWORD), true, true);
        normalId = accounts.createUser(NORMAL_EMAIL, encoder.encode(PASSWORD), true, false);
        admin = login(ADMIN_EMAIL); normal = login(NORMAL_EMAIL);
    }
    @AfterEach void restore() {
        jdbc.update("UPDATE auth_user SET admin = true, enabled = true WHERE id = ?", adminId);
        modules.enable(ConformanceModulesConfiguration.MATH_ID); modules.enable(ConformanceModulesConfiguration.STATISTICS_ID);
    }
    @AfterAll void cleanup() throws Exception {
        for (var id : records) call("DELETE", RECORDS + "/" + id, admin, null, null, null);
        for (long id : new long[] {adminId, normalId}) {
            jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id); jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
        }
        http.close();
    }
    @Test void neutralFixtureCrudAndMetadataWorkThroughHttp() throws Exception {
        var discovery = checked(call("GET", ResourceMetadata.ROOT, normal, null, null, null), HttpStatus.OK);
        assertThat(discovery.toString()).contains(ResourceConformanceConfiguration.RESOURCE);
        var response = call("POST", RECORDS, admin, "{\"code\":\"HTTP-CRUD\",\"name\":\"First\"}", null, null);
        var created = checked(response, HttpStatus.CREATED); String id = created.get("id").asString(); records.add(id);
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).contains(RECORDS + "/" + id);
        var patched = checked(call("PATCH", RECORDS + "/" + id, admin, "{\"name\":\"Edited\",\"active\":false,\"note\":null}", null, null), HttpStatus.OK);
        assertThat(patched.get("name").asString()).isEqualTo("Edited"); assertThat(patched.get("active").asBoolean()).isFalse();
        checked(call("PATCH", RECORDS + "/" + id, admin, "{\"code\":\"HTTP-CRUD\"}", null, null), HttpStatus.CONFLICT);
        checked(call("PATCH", RECORDS + "/" + id, admin, "{\"code\":3}", null, null), HttpStatus.BAD_REQUEST);
        checked(call("DELETE", RECORDS + "/" + id, admin, null, null, null), HttpStatus.NO_CONTENT);
        checked(call("GET", RECORDS + "/" + id, admin, null, null, null), HttpStatus.NOT_FOUND);
    }
    @Test void runtimeAlsoDispatchesToRealJdbcAdapterWithoutGenericPersistence() throws Exception {
        var rows = checked(call("GET", ResourceController.RECORDS + "/" + MARKERS, admin, null, null, null), HttpStatus.OK);
        assertThat(rows.get("items").size()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM erbas_persistence_marker", Integer.class));
        assertThat(rows.has("total")).isFalse(); assertThat(rows.has("order")).isFalse();
        var row = checked(call("GET", ResourceController.RECORDS + "/" + MARKERS + "/1", admin, null, null, null), HttpStatus.OK);
        assertThat(row.get("id").asInt()).isEqualTo(1);
        var unsupported = call("POST", ResourceController.RECORDS + "/" + MARKERS, admin, "{}", null, null);
        checked(unsupported, HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(unsupported.headers().firstValue(HttpHeaders.ALLOW)).contains("GET");
    }
    @Test void actionExecutionIsIndependentAuthorizedValidatedAndReturns204() throws Exception {
        var record = checked(call("POST", RECORDS, admin, "{\"code\":\"HTTP-ACTION\",\"name\":\"Action\"}", null, null), HttpStatus.CREATED);
        String id = record.get("id").asString(); records.add(id);
        String root = ResourceController.ACTIONS + "/" + ResourceConformanceConfiguration.RESOURCE;
        String target = "{\"target\":{\"id\":\"" + id + "\"}}";
        var availability = checked(call("POST", root + "/availability", admin, "{\"scope\":\"ENTITY\",\"id\":\"" + id + "\"}", null, null), HttpStatus.OK);
        assertThat(availability.get(0).get("available").asBoolean()).isTrue();
        String execute = root + "/" + ResourceConformanceConfiguration.ACTION + "/execute";
        checked(call("POST", execute, admin, target, null, null), HttpStatus.NO_CONTENT);
        checked(call("POST", execute, normal, target, null, null), HttpStatus.FORBIDDEN);
        checked(call("POST", root + "/unknown/execute", admin, "{}", null, null), HttpStatus.NOT_FOUND);
        checked(call("POST", execute, admin, "{\"target\":{\"id\":\"" + id + "\"},\"parameters\":{\"unknown\":true}}", null, null), HttpStatus.BAD_REQUEST);
    }
    @Test void genericScopedPrecedenceDoesNotChangeLegacyUsersModulesOrWms() throws Exception {
        checked(call("POST", RECORDS, null, "{", null, null), HttpStatus.UNAUTHORIZED);
        checked(call("POST", ResourceController.RECORDS + "/missing", normal, "{", null, null), HttpStatus.NOT_FOUND);
        checked(call("POST", RECORDS, normal, "{", null, null), HttpStatus.BAD_REQUEST);
        for (String legacy : List.of("/api/users", "/api/modules/missing", "/api/wms/uoms"))
            checked(call(legacy.startsWith("/api/modules") ? "PATCH" : "POST", legacy, normal, "{", null, null), HttpStatus.FORBIDDEN);
        modules.disable(ConformanceModulesConfiguration.STATISTICS_ID); modules.disable(ConformanceModulesConfiguration.MATH_ID);
        var disabled = checked(call("POST", RECORDS, normal, "{}", null, null), HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(disabled.get("code").asString()).isEqualTo(ResourceFailure.Code.module_disabled.name());
    }
    @Test void currentPersistedPermissionAndDisabledIdentityAffectNextExecutionAndMetadataEtag() throws Exception {
        var metadata = call("GET", ResourceMetadata.ROOT + "/" + ResourceConformanceConfiguration.RESOURCE, admin, null, null, null);
        checked(metadata, HttpStatus.OK); String etag = metadata.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        checked(call("GET", ResourceMetadata.ROOT + "/" + ResourceConformanceConfiguration.RESOURCE, admin, null, etag, null), HttpStatus.NOT_MODIFIED);
        jdbc.update("UPDATE auth_user SET admin = false WHERE id = ?", adminId);
        checked(call("GET", RECORDS, admin, null, null, null), HttpStatus.FORBIDDEN);
        checked(call("GET", ResourceMetadata.ROOT + "/" + ResourceConformanceConfiguration.RESOURCE, admin, null, etag, null), HttpStatus.OK);
        jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", adminId);
        checked(call("GET", RECORDS, admin, null, null, null), HttpStatus.UNAUTHORIZED);
    }
    @Test void navigationLocalizationConditionalCachingAndIdentitySeparationUseHttpNegotiation() throws Exception {
        var en = call("GET", NavigationController.PATH, admin, null, null, "en"); var body = checked(en, HttpStatus.OK);
        String etag = en.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        assertThat(en.headers().allValues(HttpHeaders.VARY).toString()).contains(HttpHeaders.AUTHORIZATION, HttpHeaders.ACCEPT_LANGUAGE);
        assertThat(etag).isEqualTo("\"" + body.get("version").asString() + "\"");
        checked(call("GET", NavigationController.PATH, admin, null, etag, "en"), HttpStatus.NOT_MODIFIED);
        checked(call("GET", NavigationController.PATH, normal, null, etag, "en"), HttpStatus.OK);
        var es = checked(call("GET", NavigationController.PATH, admin, null, etag, "es-ES"), HttpStatus.OK);
        assertThat(es.get("items").get(0).get("label").asString()).isEqualTo("Validación");
        var fr = checked(call("GET", NavigationController.PATH, admin, null, null, "fr"), HttpStatus.OK);
        assertThat(fr).isEqualTo(body);
        checked(call("GET", NavigationController.PATH, null, null, etag, null), HttpStatus.UNAUTHORIZED);
        checked(call("GET", NavigationController.PATH + "?unknown=1", admin, null, null, null), HttpStatus.BAD_REQUEST);
    }
    @Test void navigationRevalidatesPermissionAndModuleBeforeOldEtag() throws Exception {
        var first = call("GET", NavigationController.PATH, admin, null, null, null);
        checked(first, HttpStatus.OK); String etag = first.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        jdbc.update("UPDATE auth_user SET admin = false WHERE id = ?", adminId);
        var denied = checked(call("GET", NavigationController.PATH, admin, null, etag, null), HttpStatus.OK);
        assertThat(denied.get("items").isEmpty()).isTrue();
        jdbc.update("UPDATE auth_user SET admin = true WHERE id = ?", adminId);
        modules.disable(ConformanceModulesConfiguration.STATISTICS_ID); modules.disable(ConformanceModulesConfiguration.MATH_ID);
        var disabled = checked(call("GET", NavigationController.PATH, admin, null, etag, null), HttpStatus.OK);
        assertThat(disabled.get("items").isEmpty()).isTrue();
    }
    private String login(String email) throws Exception {
        return checked(call("POST", "/api/auth/login", null, ResourceJson.JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null, null), HttpStatus.OK)
                .get("accessToken").asString();
    }
    private HttpResponse<String> call(String method, String path, String token, String body, String etag, String language) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(TIMEOUT).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (etag != null) request.header(HttpHeaders.IF_NONE_MATCH, etag);
        if (language != null) request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode checked(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status.value());
        if (status == HttpStatus.NO_CONTENT || status == HttpStatus.NOT_MODIFIED) { assertThat(response.body()).isEmpty(); return null; }
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        if (status.isError()) {
            assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            var error = ResourceJson.JSON.readTree(response.body());
            assertThat(error.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("code", "errors");
            assertThat(response.body()).doesNotContain("Exception", "SQL", MARKER_PERMISSION, ResourceConformanceConfiguration.PERMISSION);
        }
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        return ResourceJson.JSON.readTree(response.body());
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean ErbasModule markerTestModule() {
            return new ErbasModule() {
                public ModuleDescriptor descriptor() { return new ModuleDescriptor(TEST_MODULE, ModuleApi.CURRENT_VERSION, List.of()); }
                public void initialize() { }
            };
        }
        @Bean ResourceRegistry.Source markerSource() {
            var id = new FieldDefinition("id", ScalarType.INT32, false, true, true, true, null, FieldDefinition.Constraints.NONE,
                    new FieldDefinition.Capabilities(true, true, false), List.of(), List.of());
            return () -> List.of(new ResourceDefinition(MARKERS, "1.0.0", TEST_MODULE, "id", List.of(id),
                    Map.of(ResourceOperation.list, MARKER_PERMISSION, ResourceOperation.get, MARKER_PERMISSION), List.of(), List.of(), List.of(), null));
        }
        @Bean FunctionalPermissionPolicy markerPermission() { return (user, permission) -> user.admin() && MARKER_PERMISSION.equals(permission); }
        @Bean ResourceExecutionAdapter markerAdapter(JdbcTemplate jdbc) {
            return new ResourceExecutionAdapter() {
                public String resourceId() { return MARKERS; }
                public ResourcePage list(com.alxarafe.erbas.auth.application.UserIdentity user, ResourceQuery query) {
                    var records = jdbc.query("SELECT marker_id FROM erbas_persistence_marker", (rs, index) -> Map.<String, Object>of("id", BigInteger.valueOf(rs.getInt(1))));
                    var selected = records.stream().filter(query::matches).sorted(query.comparator()).toList();
                    var items = query.offset().compareTo(BigInteger.valueOf(selected.size())) >= 0 ? List.<Map<String, Object>>of()
                            : selected.subList(query.offset().intValueExact(), Math.min(selected.size(), query.offset().intValueExact() + query.limit()));
                    return new ResourcePage(items, query.offset(), query.limit(), null);
                }
                public Optional<Map<String, Object>> get(com.alxarafe.erbas.auth.application.UserIdentity user, Object id) {
                    return jdbc.query("SELECT marker_id FROM erbas_persistence_marker WHERE marker_id = ?", (rs, index) -> Map.<String, Object>of("id", BigInteger.valueOf(rs.getInt(1))), ((BigInteger) id).intValueExact()).stream().findFirst();
                }
                public Map<String, Object> create(com.alxarafe.erbas.auth.application.UserIdentity user, Map<String, Object> input) { throw new AssertionError("Undeclared create"); }
                public Map<String, Object> update(com.alxarafe.erbas.auth.application.UserIdentity user, Object id, Map<String, Object> input) { throw new AssertionError("Undeclared update"); }
                public boolean delete(com.alxarafe.erbas.auth.application.UserIdentity user, Object id) { throw new AssertionError("Undeclared delete"); }
            };
        }
    }
}
