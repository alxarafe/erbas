package com.alxarafe.erbas;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.capabilities.http.CapabilityController;
import com.alxarafe.erbas.capabilities.http.CapabilityInvocationController;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.WmsCapabilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityDiscoveryIT {
    private static final String PASSWORD = "capability-discovery-fixture";
    private static final String ADMIN_EMAIL = "capability-admin@example.test";
    private static final String NORMAL_EMAIL = "capability-normal@example.test";
    private static final String PATH = CapabilityController.PATH;
    private static final String LOGIN = "/api/auth/login";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Capability> declarations = new WmsCapabilities().capabilities().stream()
            .sorted(java.util.Comparator.comparing(Capability::id)).toList();
    private long adminId;
    private long normalId;
    private String admin;
    private String normal;

    @BeforeAll void provisionOwnedAccounts() throws Exception {
        var hash = encoder.encode(PASSWORD);
        adminId = accounts.createUser(ADMIN_EMAIL, hash, true, true);
        normalId = accounts.createUser(NORMAL_EMAIL, hash, true, false);
        admin = login(ADMIN_EMAIL); normal = login(NORMAL_EMAIL);
    }
    @AfterAll void removeOnlyOwnedAccounts() {
        http.close();
        for (long id : new long[] {adminId, normalId}) {
            if (id > 0) {
                jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
                jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
            }
        }
    }
    @Test void adminSeesExactlyClosedDescriptorsAndIdenticalDetails() throws Exception {
        var body = checked(get(PATH, admin), HttpStatus.OK);
        assertThat(body.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("items", "offset", "limit", "total", "order");
        assertThat(body.get("total").asInt()).isEqualTo(declarations.size());
        assertThat(body.get("offset").asLong()).isZero();
        assertThat(body.get("limit").asInt()).isEqualTo(com.alxarafe.erbas.adapter.http.PagedCollection.DEFAULT_LIMIT);
        assertThat(body.get("items")).isEqualTo(json.valueToTree(declarations));
        assertThat(body.get("order")).isEqualTo(json.readTree("[{\"field\":\"id\",\"direction\":\"asc\"}]"));
        for (var declaration : declarations) {
            var detail = checked(get(PATH + "/" + declaration.id(), admin), HttpStatus.OK);
            assertThat(detail).isEqualTo(json.valueToTree(declaration));
            assertThat(detail.size()).isEqualTo(9);
            assertThat(detail.get("inputSchema").size()).isEqualTo(1);
            assertThat(detail.get("inputSchema").get("$ref").asString()).isEqualTo(declaration.inputSchema().ref());
        }
    }
    @Test void normalUserGetsEmptyEffectiveCatalogueAndMaskedDetail() throws Exception {
        var empty = checked(get(PATH, normal), HttpStatus.OK);
        assertThat(empty.get("items").size()).isZero(); assertThat(empty.get("total").asInt()).isZero();
        var hidden = checked(get(PATH + "/" + declarations.getFirst().id(), normal), HttpStatus.NOT_FOUND);
        assertThat(hidden).isEqualTo(error(CapabilityController.NOT_FOUND));
        assertThat(checked(get(PATH + "/unknown.resource.read", admin), HttpStatus.NOT_FOUND)).isEqualTo(hidden);
    }
    @Test void realModulePatchHidesAndRestoresDiscoveryWithoutRestart() throws Exception {
        String modulePath = ModuleController.MODULES_PATH + "/" + WmsModule.ID;
        checked(call("PATCH", modulePath, admin, "{\"enabled\":false}"), HttpStatus.OK);
        try {
            var empty = checked(get(PATH, admin), HttpStatus.OK);
            assertThat(empty.get("items").size()).isZero(); assertThat(empty.get("total").asInt()).isZero();
            assertThat(checked(get(PATH + "/" + declarations.getFirst().id(), admin), HttpStatus.NOT_FOUND))
                    .isEqualTo(error(CapabilityController.NOT_FOUND));
            checked(get("/api/wms/uoms", admin), HttpStatus.SERVICE_UNAVAILABLE);
        } finally { checked(call("PATCH", modulePath, admin, "{\"enabled\":true}"), HttpStatus.OK); }
        assertThat(checked(get(PATH, admin), HttpStatus.OK).get("items")).isEqualTo(json.valueToTree(declarations));
    }
    @ParameterizedTest
    @ValueSource(strings = {"offset=-1", "limit=0", "limit=101", "limit=1&limit=2", "offset=0&offset=1", "unknown=1",
            "offset=", "offset=%20", "offset=1.0", "offset=1e1", "limit=", "limit=%20", "limit=1.0", "limit=1e1"})
    void rejectsMalformedOrRepeatedCollectionQuery(String query) throws Exception {
        assertThat(checked(get(PATH + "?" + query, admin), HttpStatus.BAD_REQUEST)).isEqualTo(error(CapabilityController.INVALID_REQUEST));
    }
    @ParameterizedTest
    @ValueSource(strings = {"Wms.uoms.list", "wms..list", "wms.uoms", "wms.uoms.list.extra", "wms.uoms.list?limit=1"})
    void rejectsMalformedOrQueriedDetail(String id) throws Exception {
        assertThat(checked(get(PATH + "/" + id, admin), HttpStatus.BAD_REQUEST)).isEqualTo(error(CapabilityController.INVALID_REQUEST));
    }
    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2", "3", "999999999999999999999999999999999999999999999999999999"})
    void pagesAfterEffectiveCountWithoutOverflow(String offset) throws Exception {
        var body = checked(get(PATH + "?offset=" + offset + "&limit=1", admin), HttpStatus.OK);
        assertThat(body.get("offset")).isEqualTo(json.readTree(offset));
        assertThat(body.get("limit").asInt()).isEqualTo(1);
        assertThat(body.get("total").asInt()).isEqualTo(declarations.size());
        var expected = new BigInteger(offset).compareTo(BigInteger.valueOf(declarations.size())) >= 0
                ? List.<Capability>of() : List.of(declarations.get(Integer.parseInt(offset)));
        assertThat(body.get("items")).isEqualTo(json.valueToTree(expected));
    }
    @Test void authenticationAlwaysPrecedesQueryAndIdValidation() throws Exception {
        for (var token : new String[] {null, "invalid-token"}) assertUnauthorizedEverywhere(token);
        jdbc.update("UPDATE auth_access_token SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE user_id = ?", normalId);
        try { assertUnauthorizedEverywhere(normal); }
        finally { jdbc.update("UPDATE auth_access_token SET expires_at = now() + interval '1 hour' WHERE user_id = ?", normalId); }
        jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", normalId);
        try { assertUnauthorizedEverywhere(normal); }
        finally { jdbc.update("UPDATE auth_user SET enabled = true WHERE id = ?", normalId); }
    }
    @Test void persistedDemotionAffectsSameBearerOnNextDiscovery() throws Exception {
        jdbc.update("UPDATE auth_user SET admin = false WHERE id = ?", adminId);
        try {
            assertThat(checked(get(PATH, admin), HttpStatus.OK).get("total").asInt()).isZero();
            assertThat(checked(get(PATH + "/" + declarations.getFirst().id(), admin), HttpStatus.NOT_FOUND))
                    .isEqualTo(error(CapabilityController.NOT_FOUND));
        } finally { jdbc.update("UPDATE auth_user SET admin = true WHERE id = ?", adminId); }
    }
    @Test void allThreeRealPostgresHandlersEqualDirectGetForDefaultsAndWindow() throws Exception {
        var invocationIds = new java.util.HashSet<String>();
        for (String resource : List.of("uoms", "storage-attributes", "item-families")) {
            String direct = "/api/wms/" + resource;
            String textField = resource.equals("uoms") ? "description" : "name";
            String table = switch (resource) {
                case "uoms" -> "wms_uoms";
                case "storage-attributes" -> "wms_storage_attributes";
                default -> "wms_item_families";
            };
            var owned = new java.util.ArrayList<java.util.UUID>();
            try {
                for (String code : List.of("IVTESTA", "IVTESTB", "IVTESTC")) {
                    var item = checked(call("POST", direct, admin, json.writeValueAsString(Map.of("code", code,
                            textField, "Invocation fixture"))), HttpStatus.CREATED);
                    owned.add(java.util.UUID.fromString(item.get("id").asString()));
                }
                for (String input : List.of("{}", "{\"offset\":1,\"limit\":1}")) {
                    String id = "wms." + resource + ".list";
                    var response = checked(invoke(id, admin, "{\"input\":" + input + "}"), HttpStatus.OK);
                    assertThat(response.properties()).extracting(Map.Entry::getKey)
                            .containsExactlyInAnyOrder("invocationId", "capabilityId", "output");
                    assertThat(response.get("capabilityId").asString()).isEqualTo(id);
                    assertThat(response.get("invocationId").asString()).isNotBlank();
                    assertThat(invocationIds.add(response.get("invocationId").asString())).isTrue();
                    var expected = checked(get(direct + (input.equals("{}") ? "" : "?offset=1&limit=1"), admin), HttpStatus.OK);
                    assertThat(response.get("output")).isEqualTo(expected);
                    assertThat(expected.get("total").asLong()).isGreaterThanOrEqualTo(owned.size());
                }
            } finally {
                for (var id : owned) jdbc.update("DELETE FROM " + table + " WHERE id = ?", id);
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"input\":{}}", "{\"input\":{\"limit\":0}}", "{"})
    void normalUserNeverReachesInputValidation(String body) throws Exception {
        assertThat(checked(invoke(WmsCapabilities.UOMS_LIST, normal, body), HttpStatus.NOT_FOUND))
                .isEqualTo(error(CapabilityController.NOT_FOUND));
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[]", "1", "true", "\"text\"", "{\"input\":null}",
            "{\"input\":[]}", "{\"input\":\"text\"}", "{\"input\":1}", "{\"input\":{},\"caller\":\"user\"}",
            "{\"input\":{\"unknown\":1}}", "{\"input\":{\"permissions\":[]}}", "{\"input\":{},\"input\":{}}",
            "{\"input\":{\"offset\":0,\"offset\":1}}", "{\"input\":{}} {}", "{\"input\":{\"offset\":null}}",
            "{\"input\":{\"offset\":-1}}", "{\"input\":{\"limit\":0}}", "{\"input\":{\"limit\":101}}",
            "{\"input\":{\"offset\":\"1\"}}", "{\"input\":{\"limit\":true}}", "{\"input\":{\"offset\":1.1}}",
            "{\"input\":{\"limit\":1.5}}"})
    void authorizedInvocationStrictlyRejectsInvalidBody(String body) throws Exception {
        assertThat(checked(invoke(WmsCapabilities.UOMS_LIST, admin, body), HttpStatus.BAD_REQUEST))
                .isEqualTo(error(CapabilityController.INVALID_REQUEST));
    }
    @Test void invocationAuthenticationPrecedesInvalidIdAndMalformedJson() throws Exception {
        for (String token : new String[] {null, "invalid-token"}) {
            assertThat(checked(invoke("INVALID", token, "{"), HttpStatus.UNAUTHORIZED)).isEqualTo(error("unauthorized"));
        }
        jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", normalId);
        try { checked(invoke("INVALID", normal, "{"), HttpStatus.UNAUTHORIZED); }
        finally { jdbc.update("UPDATE auth_user SET enabled = true WHERE id = ?", normalId); }
        jdbc.update("UPDATE auth_access_token SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE user_id = ?", normalId);
        try { checked(invoke("INVALID", normal, "{"), HttpStatus.UNAUTHORIZED); }
        finally { jdbc.update("UPDATE auth_access_token SET expires_at = now() + interval '1 hour' WHERE user_id = ?", normalId); }
        assertThat(checked(invoke("INVALID", admin, "{"), HttpStatus.BAD_REQUEST)).isEqualTo(error(CapabilityController.INVALID_REQUEST));
        assertThat(checked(invoke("unknown.resource.read", admin, "{"), HttpStatus.NOT_FOUND))
                .isEqualTo(error(CapabilityController.NOT_FOUND));
    }
    @Test void invocationRechecksDisabledModuleAndDemotedUserWithoutRestart() throws Exception {
        checked(get(PATH + "/" + WmsCapabilities.UOMS_LIST, admin), HttpStatus.OK);
        checked(invoke(WmsCapabilities.UOMS_LIST, admin, "{\"input\":{}}"), HttpStatus.OK);
        String modulePath = ModuleController.MODULES_PATH + "/" + WmsModule.ID;
        checked(call("PATCH", modulePath, admin, "{\"enabled\":false}"), HttpStatus.OK);
        try {
            for (String body : List.of("{\"input\":{}}", "{")) {
                assertThat(checked(invoke(WmsCapabilities.UOMS_LIST, admin, body), HttpStatus.NOT_FOUND))
                        .isEqualTo(error(CapabilityController.NOT_FOUND));
            }
            checked(get("/api/wms/uoms", admin), HttpStatus.SERVICE_UNAVAILABLE);
        } finally { checked(call("PATCH", modulePath, admin, "{\"enabled\":true}"), HttpStatus.OK); }
        checked(invoke(WmsCapabilities.UOMS_LIST, admin, "{\"input\":{}}"), HttpStatus.OK);
        jdbc.update("UPDATE auth_user SET admin = false WHERE id = ?", adminId);
        try {
            assertThat(checked(invoke(WmsCapabilities.UOMS_LIST, admin, "{"), HttpStatus.NOT_FOUND))
                    .isEqualTo(error(CapabilityController.NOT_FOUND));
        } finally { jdbc.update("UPDATE auth_user SET admin = true WHERE id = ?", adminId); }
        checked(invoke(WmsCapabilities.UOMS_LIST, admin, "{\"input\":{}}"), HttpStatus.OK);
    }
    private HttpResponse<String> invoke(String id, String token, String body) throws Exception {
        return call("POST", PATH + "/" + id + CapabilityInvocationController.SUFFIX, token, body);
    }
    private void assertUnauthorizedEverywhere(String token) throws Exception {
        for (String path : List.of(PATH, PATH + "?limit=0", PATH + "/INVALID", PATH + "/INVALID?unknown=1")) {
            assertThat(checked(get(path, token), HttpStatus.UNAUTHORIZED)).isEqualTo(error("unauthorized"));
        }
    }
    private JsonNode error(String code) { return json.valueToTree(com.alxarafe.erbas.adapter.http.ContractError.of(code)); }
    private String login(String email) throws Exception {
        return checked(call("POST", LOGIN, null, json.writeValueAsString(Map.of("email", email, "password", PASSWORD))),
                HttpStatus.OK).get("accessToken").asString();
    }
    private HttpResponse<String> get(String path, String token) throws Exception { return call("GET", path, token, null); }
    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(TIMEOUT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode checked(HttpResponse<String> response, HttpStatus status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status.value());
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        return json.readTree(response.body());
    }
}
