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
    @Test void noCapabilityInvocationEndpointWasIntroduced() throws Exception {
        assertThat(call("POST", PATH + "/" + declarations.getFirst().id() + "/invocations", admin, "{}").statusCode())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
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
