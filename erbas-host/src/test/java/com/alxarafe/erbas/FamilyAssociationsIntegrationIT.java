package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.ItemFamilyCatalogue;
import com.alxarafe.erbas.wms.application.FamilyAssociations;
import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import com.alxarafe.erbas.wms.http.ItemFamilyController;
import com.alxarafe.erbas.wms.infrastructure.JdbcFamilyAssociationStore;
import com.alxarafe.erbas.wms.infrastructure.JdbcItemFamilyStore;
import com.alxarafe.erbas.wms.infrastructure.JdbcStorageAttributeStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static com.alxarafe.erbas.wms.http.FamilyAssociationController.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FamilyAssociationsIntegrationIT {
    private static final String ADMIN_EMAIL = "associations-admin@example.test";
    private static final String USER_EMAIL = "associations-user@example.test";
    private static final String PASSWORD = "associations-fixture-password";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String BEARER = "Bearer";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int WAIT_SECONDS = 10;
    private static final String ROLLBACK_CONSTRAINT = "association_it_atomic_failure";
    private static final UUID ABSENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Value("${local.server.port}") private int port;
    @Autowired private ItemFamilyCatalogue families;
    @Autowired private FamilyAssociations associations;
    @Autowired private StorageAttributeCatalogue attributes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ModuleStateService states;
    @Autowired private PlatformTransactionManager transactions;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private long adminId;
    private long userId;
    private String admin;
    private String user;
    private UUID family;
    private UUID otherFamily;
    private StorageAttribute a, b, c, d;

    @BeforeAll
    void authenticateRealAccounts() throws Exception {
        adminId = accounts.createUser(ADMIN_EMAIL, encoder.encode(PASSWORD), true, true);
        userId = accounts.createUser(USER_EMAIL, encoder.encode(PASSWORD), true, false);
        admin = login(ADMIN_EMAIL);
        user = login(USER_EMAIL);
    }

    @BeforeEach
    void createOnlyOwnedMasters() {
        states.enable(WmsModule.ID);
        family = families.create("ASSOC_FAMILY", " Family ").family().id();
        otherFamily = null;
        a = attributes.create("ASSOC_A", "A").attribute();
        b = attributes.create("ASSOC_B", "B").attribute();
        c = attributes.create("ASSOC_C", "C").attribute();
        d = attributes.create("ASSOC_D", "D").attribute();
    }

    @AfterEach
    void removeOwnedRelationsBeforeMasters() {
        states.enable(WmsModule.ID);
        for (UUID id : Stream.of(family, otherFamily).filter(java.util.Objects::nonNull).toList()) {
            jdbc.update("DELETE FROM " + JdbcFamilyAssociationStore.TABLE + " WHERE item_family_id = ?", id);
            jdbc.update("DELETE FROM " + JdbcItemFamilyStore.TABLE + " WHERE id = ?", id);
        }
        for (var attribute : Stream.of(a, b, c, d).filter(java.util.Objects::nonNull).toList()) {
            jdbc.update("DELETE FROM " + JdbcStorageAttributeStore.TABLE + " WHERE id = ?", attribute.id());
        }
    }

    @AfterAll
    void removeOwnedAccounts() {
        for (long id : new long[] {adminId, userId}) if (id != 0) {
            jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
        }
        client.close();
    }

    @Test
    void getReturnsEmptyCompleteMetadataAndOrdinalPaginatedMasterRepresentations() throws Exception {
        page(List.of());
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(c.id(), a.id(), b.id()))), HttpStatus.NO_CONTENT);
        page(List.of(a, b, c));
        var response = call("GET", path() + "?offset=1&limit=1", null, admin, "en");
        ok(response, HttpStatus.OK);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(envelope(List.of(b), 1, 1, 3)));
        for (String offset : List.of("3", "9223372036854775808")) {
            var beyond = call("GET", path() + "?offset=" + offset, null, admin, "en");
            ok(beyond, HttpStatus.OK);
            var value = json.readTree(beyond.body());
            assertThat(value.get("items").isEmpty()).isTrue();
            assertThat(value.get("total").asInt()).isEqualTo(3);
        }
        error(call("GET", path(ABSENT), null, admin, "en"), HttpStatus.NOT_FOUND, FAMILY_MISSING,
                detail(FAMILY_MISSING, FAMILY_RESOURCE, ABSENT, null));
    }

    @Test
    void postCreatesOnePairWithExactLocationAndDuplicateDoesNotMutate() throws Exception {
        var created = change("POST", Map.of(ATTRIBUTE_ID, a.id()));
        ok(created, HttpStatus.CREATED);
        assertThat(created.headers().firstValue(HttpHeaders.LOCATION)).contains(path() + "/" + a.id());
        assertThat(json.readTree(created.body())).isEqualTo(json.valueToTree(Map.of("itemFamilyId", family, "storageAttribute", a)));
        error(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CONFLICT, RELATION_CONFLICT,
                detail(ALREADY_EXISTS, ATTRIBUTE_RESOURCE, a.id(), "/" + ATTRIBUTE_ID));
        page(List.of(a));
    }

    @Test
    void concurrentPostOfSamePairHasOneCreationOneConflictAndOnePersistedPair() throws Exception {
        var results = race(() -> change("POST", Map.of(ATTRIBUTE_ID, a.id())), () -> change("POST", Map.of(ATTRIBUTE_ID, a.id())));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
        for (var result : results) if (result.statusCode() == HttpStatus.CONFLICT.value()) {
            error(result, HttpStatus.CONFLICT, RELATION_CONFLICT, detail(ALREADY_EXISTS, ATTRIBUTE_RESOURCE, a.id(), "/" + ATTRIBUTE_ID));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcFamilyAssociationStore.TABLE + " WHERE item_family_id = ?", Integer.class, family)).isEqualTo(1);
        page(List.of(a));
    }

    @Test
    void putReplacesExactlyIsIdempotentAndEmptyArrayPreservesBothCatalogues() throws Exception {
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(a.id(), b.id()))), HttpStatus.NO_CONTENT);
        page(List.of(a, b));
        for (int repeat = 0; repeat < 2; repeat++) {
            ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(c.id()))), HttpStatus.NO_CONTENT);
            page(List.of(c));
        }
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of())), HttpStatus.NO_CONTENT);
        page(List.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcStorageAttributeStore.TABLE + " WHERE id IN (?, ?, ?, ?)", Integer.class, a.id(), b.id(), c.id(), d.id())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcItemFamilyStore.TABLE + " WHERE id = ?", Integer.class, family)).isEqualTo(1);
    }

    @Test
    void failedPutAndPatchReturnAllSelectedStageDetailsAndLeavePersistedStateUnchanged() throws Exception {
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(a.id(), b.id()))), HttpStatus.NO_CONTENT);
        error(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(c.id(), ABSENT))), HttpStatus.NOT_FOUND, ATTRIBUTE_MISSING,
                detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, "/" + ATTRIBUTE_IDS + "/1"));
        page(List.of(a, b));
        error(change("PATCH", Map.of(ADD, List.of(b.id(), c.id()), REMOVE, List.of(d.id()))), HttpStatus.CONFLICT, RELATIONS_CONFLICT,
                detail(ALREADY_EXISTS, ATTRIBUTE_RESOURCE, b.id(), "/" + ADD + "/0"),
                detail(NOT_ASSOCIATED, ATTRIBUTE_RESOURCE, d.id(), "/" + REMOVE + "/0"));
        page(List.of(a, b));
        error(change("PATCH", Map.of(ADD, List.of(b.id(), ABSENT), REMOVE, List.of(d.id()))), HttpStatus.NOT_FOUND, ATTRIBUTE_MISSING,
                detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, "/" + ADD + "/1"));
        page(List.of(a, b));
        error(call("POST", path(ABSENT), json.writeValueAsString(Map.of(ATTRIBUTE_ID, ABSENT)), admin, "en"), HttpStatus.NOT_FOUND, RESOURCES_MISSING,
                detail(FAMILY_MISSING, FAMILY_RESOURCE, ABSENT, null), detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, "/" + ATTRIBUTE_ID));
    }

    @Test
    void patchSupportsStrictAddRemoveCombinedAndCrossArrayOverlapUsesPreState() throws Exception {
        ok(change("PATCH", Map.of(ADD, List.of(a.id()))), HttpStatus.NO_CONTENT);
        page(List.of(a));
        ok(change("PATCH", Map.of(REMOVE, List.of(a.id()))), HttpStatus.NO_CONTENT);
        page(List.of());
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(a.id(), b.id()))), HttpStatus.NO_CONTENT);
        ok(change("PATCH", Map.of(ADD, List.of(c.id()), REMOVE, List.of(a.id()))), HttpStatus.NO_CONTENT);
        page(List.of(b, c));
        error(change("PATCH", Map.of(ADD, List.of(b.id()), REMOVE, List.of(b.id()))), HttpStatus.CONFLICT, RELATIONS_CONFLICT,
                detail(ALREADY_EXISTS, ATTRIBUTE_RESOURCE, b.id(), "/" + ADD + "/0"));
        error(change("PATCH", Map.of(ADD, List.of(d.id()), REMOVE, List.of(d.id()))), HttpStatus.CONFLICT, RELATIONS_CONFLICT,
                detail(NOT_ASSOCIATED, ATTRIBUTE_RESOURCE, d.id(), "/" + REMOVE + "/0"));
        error(change("PATCH", Map.of(ADD, List.of(ABSENT), REMOVE, List.of(ABSENT))), HttpStatus.NOT_FOUND, RESOURCES_MISSING,
                detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, "/" + ADD + "/0"), detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, "/" + REMOVE + "/0"));
        page(List.of(b, c));
    }

    @Test
    void deleteDistinguishesMissingFamilyMasterAndRelationAndHasNoBody() throws Exception {
        ok(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CREATED);
        ok(call("DELETE", path() + "/" + a.id(), null, admin, "en"), HttpStatus.NO_CONTENT);
        page(List.of());
        error(call("DELETE", path() + "/" + a.id(), null, admin, "en"), HttpStatus.NOT_FOUND, RELATION_MISSING,
                detail(RELATION_MISSING, RELATION_RESOURCE, a.id(), null));
        error(call("DELETE", path(ABSENT) + "/" + a.id(), null, admin, "en"), HttpStatus.NOT_FOUND, FAMILY_MISSING,
                detail(FAMILY_MISSING, FAMILY_RESOURCE, ABSENT, null));
        error(call("DELETE", path() + "/" + ABSENT, null, admin, "en"), HttpStatus.NOT_FOUND, ATTRIBUTE_MISSING,
                detail(ATTRIBUTE_MISSING, ATTRIBUTE_RESOURCE, ABSENT, null));
        page(List.of());
    }

    @Test
    void aMasterCanBelongToTwoFamiliesWithoutBeingDeletedWhenOneRelationIsRemoved() throws Exception {
        otherFamily = families.create("ASSOC_OTHER", "Other").family().id();
        ok(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CREATED);
        ok(call("POST", path(otherFamily), json.writeValueAsString(Map.of(ATTRIBUTE_ID, a.id())), admin, "en"), HttpStatus.CREATED);
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of())), HttpStatus.NO_CONTENT);
        page(List.of());
        var response = call("GET", path(otherFamily), null, admin, "en");
        assertThat(json.readTree(response.body()).get("items")).isEqualTo(json.valueToTree(List.of(a)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcStorageAttributeStore.TABLE + " WHERE id = ?", Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void strictJsonRejectsMalformedClosedWrongCaseDuplicateAndInvalidArrayInputs() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH")) for (String input : List.of("", " ", "{", "[]", "null", "true", "42", "\"x\"", "{}", "{\"unknown\":[]}")) {
            simpleError(call(method, path(), input, admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (String input : List.of("{\"storageAttributeId\":null}", "{\"storageAttributeId\":42}", "{\"StorageAttributeId\":\"" + a.id() + "\"}",
                "{\"storageAttributeId\":\"" + a.id() + "\",\"extra\":true}",
                "{\"storageAttributeId\":\"" + a.id() + "\",\"storageAttributeId\":\"" + b.id() + "\"}")) {
            simpleError(call("POST", path(), input, admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (Object input : List.of(Map.of(ATTRIBUTE_IDS, List.of(a.id(), a.id())), Map.of(ATTRIBUTE_IDS, List.of(42)), Map.of(ATTRIBUTE_IDS, "x"))) {
            simpleError(change("PUT", input), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (Object input : List.of(Map.of(ADD, List.of(a.id(), a.id())), Map.of(REMOVE, List.of(a.id(), a.id())),
                Map.of(ADD, List.of(), REMOVE, List.of()), Map.of(ADD, false), Map.of("Add", List.of(a.id())))) {
            simpleError(change("PATCH", input), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (String method : List.of("PUT", "PATCH")) {
            String field = method.equals("PUT") ? ATTRIBUTE_IDS : ADD;
            simpleError(call(method, path(), "{\"" + field + "\":[],\"" + field + "\":[]}", admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
            simpleError(call(method, path(), "{\"" + field + "\":null}", admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        page(List.of());
    }

    @Test
    void strictQueryAndCanonicalPathValidationPrecedeExistence() throws Exception {
        for (String query : List.of("?unknown=1", "?offset=", "?offset=%200", "?offset=1e2", "?offset=0.5", "?offset=0&offset=1", "?limit=0", "?limit=" + (PagedCollection.MAXIMUM_LIMIT + 1), "?limit=1&limit=2", "?limit=%201", "?limit=1.5")) {
            simpleError(call("GET", path(ABSENT) + query, null, admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (String value : List.of("bad", "00000000-0000-0000-0000-000000000000", "ABCDEFAB-1234-1234-1234-123456789012")) {
            simpleError(call("GET", ItemFamilyController.ITEM_FAMILIES_PATH + "/" + value + "/" + ASSOCIATION_SEGMENT, null, admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
            simpleError(call("DELETE", path() + "/" + value, null, admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
            simpleError(change("POST", Map.of(ATTRIBUTE_ID, value)), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            simpleError(call(method, path() + (method.equals("DELETE") ? "/" + a.id() : "") + "?offset=0", "{}", admin, "en"), HttpStatus.BAD_REQUEST, ItemFamilyController.INVALID_REQUEST);
        }
        page(List.of());
    }

    @Test
    void authenticationAdminAndDynamicGatePrecedeInvalidRequestsForEveryAssociationMethod() throws Exception {
        ok(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CREATED);
        for (boolean enabled : List.of(true, false)) {
            ok(call("PATCH", ModuleController.MODULES_PATH + "/" + WmsModule.ID, json.writeValueAsString(Map.of(ModuleController.ENABLED_FIELD, enabled)), admin, "en"), HttpStatus.OK);
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                String broken = ItemFamilyController.ITEM_FAMILIES_PATH + "/invalid/" + ASSOCIATION_SEGMENT + (method.equals("DELETE") ? "/invalid" : "");
                simpleError(call(method, broken, "{", null, "en"), HttpStatus.UNAUTHORIZED, "unauthorized");
                simpleError(call(method, broken, "{", user, "en"), HttpStatus.FORBIDDEN, "forbidden");
                simpleError(call(method, broken, "{", admin, "en"), enabled ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE,
                        enabled ? ItemFamilyController.INVALID_REQUEST : "module_disabled");
            }
        }
        ok(call("PATCH", ModuleController.MODULES_PATH + "/" + WmsModule.ID, json.writeValueAsString(Map.of(ModuleController.ENABLED_FIELD, true)), admin, "en"), HttpStatus.OK);
        page(List.of(a));
        assertThat(jdbc.queryForObject("SELECT name FROM " + JdbcItemFamilyStore.TABLE + " WHERE id = ?", String.class, family)).isEqualTo("Family");
    }

    @Test
    void localesLeaveTechnicalErrorsAndCompleteMasterContentUnchanged() throws Exception {
        ok(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CREATED);
        for (String locale : List.of("en", "es")) {
            var response = call("GET", path(), null, admin, locale);
            ok(response, HttpStatus.OK);
            assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(envelope(List.of(a), 0, PagedCollection.DEFAULT_LIMIT, 1)));
            error(call("POST", path(), json.writeValueAsString(Map.of(ATTRIBUTE_ID, a.id())), admin, locale), HttpStatus.CONFLICT, RELATION_CONFLICT,
                    detail(ALREADY_EXISTS, ATTRIBUTE_RESOURCE, a.id(), "/" + ATTRIBUTE_ID));
        }
    }

    @Test
    void databaseEnforcesRequiredPairForeignKeysAndCompositeUniqueness() throws Exception {
        ok(change("POST", Map.of(ATTRIBUTE_ID, a.id())), HttpStatus.CREATED);
        var transaction = new TransactionTemplate(transactions);
        for (Object[] pair : List.of(new Object[]{family, a.id()}, new Object[]{ABSENT, a.id()}, new Object[]{family, ABSENT}, new Object[]{null, a.id()}, new Object[]{family, null})) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update("INSERT INTO " + JdbcFamilyAssociationStore.TABLE + " (item_family_id, storage_attribute_id) VALUES (?, ?)", pair)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        page(List.of(a));
    }

    @Test
    void concurrentReplacementsRemainWholeAndConcurrentPatchesRetainBothAdditions() throws Exception {
        for (var response : race(() -> change("PUT", Map.of(ATTRIBUTE_IDS, List.of(a.id()))), () -> change("PUT", Map.of(ATTRIBUTE_IDS, List.of(b.id()))))) ok(response, HttpStatus.NO_CONTENT);
        var result = json.readTree(call("GET", path(), null, admin, "en").body());
        assertThat(result.get("items").size()).isEqualTo(1);
        assertThat(result.get("items").get(0).get("id").asString()).isIn(a.id().toString(), b.id().toString());
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of())), HttpStatus.NO_CONTENT);
        for (var response : race(() -> change("PATCH", Map.of(ADD, List.of(a.id()))), () -> change("PATCH", Map.of(ADD, List.of(b.id()))))) ok(response, HttpStatus.NO_CONTENT);
        page(List.of(a, b));
    }

    @Test
    void postgresFailureAfterDeletingOriginalPairsRollsBackTheEntireReplacement() throws Exception {
        ok(change("PUT", Map.of(ATTRIBUTE_IDS, List.of(a.id(), b.id()))), HttpStatus.NO_CONTENT);
        // The temporary constraint affects only this fixture's family and forces a real write failure.
        jdbc.execute("ALTER TABLE " + JdbcFamilyAssociationStore.TABLE + " ADD CONSTRAINT " + ROLLBACK_CONSTRAINT
                + " CHECK (NOT (item_family_id = '" + family + "'::uuid AND storage_attribute_id = '" + d.id() + "'::uuid))");
        try {
            assertThatThrownBy(() -> associations.change(family, new FamilyAssociations.Command(
                    FamilyAssociations.Kind.REPLACE, List.of(c.id(), d.id()), List.of())))
                    .isInstanceOf(DataIntegrityViolationException.class);
            page(List.of(a, b));
        } finally {
            jdbc.execute("ALTER TABLE " + JdbcFamilyAssociationStore.TABLE + " DROP CONSTRAINT " + ROLLBACK_CONSTRAINT);
        }
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var one = executor.submit(() -> { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); return first.call(); });
            var two = executor.submit(() -> { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); return second.call(); });
            return List.of(one.get(WAIT_SECONDS, TimeUnit.SECONDS), two.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void page(List<StorageAttribute> expected) throws Exception {
        var response = call("GET", path(), null, admin, "en");
        ok(response, HttpStatus.OK);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(envelope(expected, 0, PagedCollection.DEFAULT_LIMIT, expected.size())));
    }
    private Map<String, Object> envelope(List<StorageAttribute> items, int offset, int limit, int total) {
        return Map.of("items", items, "offset", offset, "limit", limit, "total", total, "order", List.of(Map.of("field", "code", "direction", "asc")));
    }
    private Map<String, Object> detail(String code, String resource, UUID id, String pointer) {
        var value = new LinkedHashMap<String, Object>();
        value.put("code", code); value.put("resource", resource); value.put("resourceId", id.toString());
        if (pointer != null) value.put("path", pointer);
        return value;
    }
    @SafeVarargs
    private final void error(HttpResponse<String> response, HttpStatus status, String code, Map<String, Object>... details) {
        ok(response, status);
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of("code", code, "errors", List.of(details))));
    }
    private void simpleError(HttpResponse<String> response, HttpStatus status, String code) {
        error(response, status, code, Map.of("code", code));
    }
    private HttpResponse<String> change(String method, Object body) throws Exception { return call(method, path(), json.writeValueAsString(body), admin, "en"); }
    private String path() { return path(family); }
    private String path(UUID id) { return ItemFamilyController.ITEM_FAMILIES_PATH + "/" + id + "/" + ASSOCIATION_SEGMENT; }
    private HttpResponse<String> call(String method, String path, String body, String token, String locale) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(HTTP_TIMEOUT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT_LANGUAGE, locale);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, BEARER + " " + token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String login(String email) throws Exception {
        var response = call("POST", LOGIN_PATH, json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null, "en");
        ok(response, HttpStatus.OK);
        return json.readTree(response.body()).get("accessToken").asString();
    }
    private void ok(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).isEqualTo(status.value());
        if (status == HttpStatus.NO_CONTENT) assertThat(response.body()).isEmpty();
        else assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains(CacheControl.noStore().getHeaderValue());
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains(BEARER);
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).isEmpty();
    }
}
