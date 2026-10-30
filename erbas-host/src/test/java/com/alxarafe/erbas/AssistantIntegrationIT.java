package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.alxarafe.erbas.ai.*;
import com.alxarafe.erbas.ai.http.AssistantController;
import com.alxarafe.erbas.ai.http.AssistantJson;
import com.alxarafe.erbas.ai.infrastructure.AiConfiguration;
import com.alxarafe.erbas.ai.infrastructure.DeterministicAssistantProvider;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.capabilities.*;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.wms.http.StorageAttributeController;
import com.alxarafe.erbas.wms.http.ItemFamilyController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.ai.infrastructure.DeterministicAssistantProvider.*;
import static com.alxarafe.erbas.modularity.ConformanceModulesConfiguration.VALIDATION_PROFILE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(VALIDATION_PROFILE)
@Import(AssistantIntegrationIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantIntegrationIT {
    private static final String PASSWORD = "assistant-integration-fixture-password";
    private static final String ADMIN_EMAIL = "assistant-admin@example.test";
    private static final String NORMAL_EMAIL = "assistant-normal@example.test";
    private static final String LOGIN = "/api/auth/login";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String UOM_CODE = "AICAPU";
    private static final String STORAGE_CODE = "AICAPS";
    private static final String FAMILY_CODE = "AICAPF";
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModuleRegistry modules;
    @Autowired private ModuleStateService states;
    @Autowired private CapabilityDiscovery discovery;
    @Autowired private RecordingAiAudit aiAudit;
    @Autowired private RecordingCoreAudit coreAudit;
    @Autowired private TestPermission permission;
    @Autowired private ScriptedProvider provider;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, UUID> ownedRows = new HashMap<>();
    private long adminId;
    private long normalId;
    private String admin;
    private String normal;

    @BeforeAll void provisionOwnedFixtures() throws Exception {
        adminId = accounts.createUser(ADMIN_EMAIL, encoder.encode(PASSWORD), true, true);
        normalId = accounts.createUser(NORMAL_EMAIL, encoder.encode(PASSWORD), true, false);
        admin = login(ADMIN_EMAIL); normal = login(NORMAL_EMAIL);
        for (var fixture : List.of(
                new Catalogue(UomController.UOMS_PATH, "wms_uoms", UOM_CODE, "description", "Persisted AI unit"),
                new Catalogue(StorageAttributeController.STORAGE_ATTRIBUTES_PATH, "wms_storage_attributes", STORAGE_CODE, "name", "Persisted AI storage"),
                new Catalogue(ItemFamilyController.ITEM_FAMILIES_PATH, "wms_item_families", FAMILY_CODE, "name", "Persisted AI family"))) {
            var result = checked(call("POST", fixture.path(), admin, json.writeValueAsString(
                    Map.of("code", fixture.code(), fixture.textField(), fixture.label())), null), HttpStatus.CREATED);
            ownedRows.put(fixture.table(), UUID.fromString(result.get("id").asString()));
        }
    }
    @BeforeEach void resetOnlyTestOverrides() {
        permission.aiOnly.clear(); provider.override.set(null); provider.beforeTool.set(null);
        states.enable(AiModule.ID); states.enable(WmsModule.ID);
        aiAudit.entries.clear(); coreAudit.entries.clear();
    }
    @AfterAll void removeOnlyOwnedFixtures() {
        states.enable(AiModule.ID); states.enable(WmsModule.ID);
        ownedRows.forEach((table, id) -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id));
        for (long id : new long[] {adminId, normalId}) {
            if (id > 0) {
                jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", id);
                jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
            }
        }
        http.close();
    }
    @Test void aiIsAvailableEnabledAndNormalModuleAdministrationRestoresItWithoutRestart() throws Exception {
        assertThat(modules.modules()).extracting(module -> module.descriptor().id()).contains(AiModule.ID);
        assertThat(states.isEnabled(AiModule.ID)).isTrue();
        checked(invoke(admin, body(GREETING_REQUEST)), HttpStatus.OK);
        patchModule(AiModule.ID, false);
        try {
            assertError(invoke(admin, "{"), HttpStatus.SERVICE_UNAVAILABLE, AssistantInvocation.MODULE_DISABLED);
            assertError(invoke(normal, "{"), HttpStatus.FORBIDDEN, AssistantInvocation.FORBIDDEN);
            assertError(invoke(null, "{"), HttpStatus.UNAUTHORIZED, AssistantInvocation.UNAUTHORIZED);
        } finally { patchModule(AiModule.ID, true); }
        checked(invoke(admin, body(GREETING_REQUEST)), HttpStatus.OK);
    }
    @Test void authenticationAndPermissionAlwaysPrecedeMalformedBody() throws Exception {
        for (String token : new String[] {null, "invalid-token"}) {
            assertError(invoke(token, "{"), HttpStatus.UNAUTHORIZED, AssistantInvocation.UNAUTHORIZED);
        }
        assertError(invoke(normal, "{"), HttpStatus.FORBIDDEN, AssistantInvocation.FORBIDDEN);
        assertError(invoke(admin, "{"), HttpStatus.BAD_REQUEST, AssistantInvocation.INVALID_REQUEST);
        jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", normalId);
        try { assertError(invoke(normal, "{"), HttpStatus.UNAUTHORIZED, AssistantInvocation.UNAUTHORIZED); }
        finally { jdbc.update("UPDATE auth_user SET enabled = true WHERE id = ?", normalId); }
        jdbc.update("UPDATE auth_access_token SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE user_id = ?", normalId);
        try { assertError(invoke(normal, "{"), HttpStatus.UNAUTHORIZED, AssistantInvocation.UNAUTHORIZED); }
        finally { jdbc.update("UPDATE auth_access_token SET expires_at = now() + interval '1 hour' WHERE user_id = ?", normalId); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[]", "1", "{\"message\":null}", "{\"message\":1}",
            "{\"message\":\"\"}", "{\"message\":\" \\t\"}", "{\"message\":\"\\u0000\"}",
            "{\"message\":\"\\ud800\"}", "{\"message\":\"\\udfff\"}",
            "{\"message\":\"hello\",\"unknown\":true}", "{\"message\":\"hello\",\"invocationId\":\"spoofed\"}",
            "{\"message\":\"hello\",\"executionContext\":{}}", "{\"message\":\"a\",\"message\":\"b\"}",
            "{\"message\":\"hello\"} {}"})
    void rejectsStrictInvalidRequestsAfterGates(String raw) throws Exception {
        assertError(invoke(admin, raw), HttpStatus.BAD_REQUEST, AssistantInvocation.INVALID_REQUEST);
    }
    @Test void unicodeScalarLimitsHoldOverHttp() throws Exception {
        for (String text : List.of("a".repeat(AiText.MESSAGE_MAXIMUM_SCALARS),
                "\ud83d\ude00".repeat(AiText.MESSAGE_MAXIMUM_SCALARS))) {
            assertThat(checked(invoke(admin, body(text)), HttpStatus.OK).get("toolsUsed").size()).isZero();
            assertError(invoke(admin, body(text + "a")), HttpStatus.BAD_REQUEST, AssistantInvocation.INVALID_REQUEST);
        }
    }
    @Test void directAnswerIsClosedWithEmptyToolsAndGeneratedDistinctAiIds() throws Exception {
        var first = checked(invoke(admin, body(GREETING_REQUEST)), HttpStatus.OK);
        var second = checked(invoke(admin, body(GREETING_REQUEST)), HttpStatus.OK);
        assertThat(first.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("invocationId", "answer", "toolsUsed");
        assertThat(first.get("answer").asString()).isNotBlank();
        assertThat(first.get("toolsUsed").size()).isZero();
        assertThat(first.get("invocationId").asString()).isNotBlank().isNotEqualTo(second.get("invocationId").asString());
        assertThat(coreAudit.entries).isEmpty();
    }
    @Test void oneAndMultipleToolsUseActualPostgresDataAndLinkBothAuditsInOrder() throws Exception {
        for (String message : List.of(ONE_READ_REQUEST, MULTIPLE_READ_REQUEST)) {
            var response = checked(invoke(admin, body(message)), HttpStatus.OK);
            var expected = message.equals(ONE_READ_REQUEST) ? List.of(UOMS_LIST)
                    : List.of(UOMS_LIST, STORAGE_ATTRIBUTES_LIST, ITEM_FAMILIES_LIST);
            assertThat(response.get("toolsUsed")).hasSize(expected.size());
            var ids = new HashSet<String>();
            int index = 0;
            for (var usage : response.get("toolsUsed")) {
                assertThat(usage.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("capabilityId", "invocationId");
                assertThat(usage.get("capabilityId").asString()).isEqualTo(expected.get(index++));
                String coreId = usage.get("invocationId").asString();
                assertThat(coreId).isNotBlank().isNotEqualTo(response.get("invocationId").asString());
                assertThat(ids.add(coreId)).isTrue();
                assertThat(coreAudit.entries).anySatisfy(entry -> {
                    assertThat(entry.invocationId()).isEqualTo(coreId);
                    assertThat(entry.module()).isEqualTo(WmsModule.ID);
                    assertThat(entry.subject()).isEqualTo(Long.toString(adminId));
                    assertThat(entry.outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.completed);
                });
            }
            var direct = checked(call("GET", UomController.UOMS_PATH, admin, null, null), HttpStatus.OK);
            var persisted = java.util.stream.StreamSupport.stream(direct.get("items").spliterator(), false)
                    .filter(item -> UOM_CODE.equals(item.get("code").asString())).findFirst().orElseThrow();
            assertThat(response.get("answer").asString()).contains(persisted.get("code").asString(), persisted.get("description").asString());
            if (expected.size() > 1) {
                assertThat(response.get("answer").asString()).contains(STORAGE_CODE, FAMILY_CODE, "Persisted AI storage", "Persisted AI family");
            }
            var audit = aiAudit.entries.getLast();
            assertThat(audit.invocationId()).isEqualTo(response.get("invocationId").asString());
            assertThat(json.<JsonNode>valueToTree(audit.toolsUsed())).isEqualTo(response.get("toolsUsed"));
            assertThat(audit.toString()).doesNotContain(message, UOM_CODE, STORAGE_CODE, FAMILY_CODE, PASSWORD);
        }
    }
    @Test void answerChangesWithPersistedStateRatherThanKnownFixtureData() throws Exception {
        String changed = "Updated persisted unit fact";
        jdbc.update("UPDATE wms_uoms SET description = ? WHERE id = ?", changed, ownedRows.get("wms_uoms"));
        try {
            var response = checked(invoke(admin, body(ONE_READ_REQUEST)), HttpStatus.OK);
            var direct = checked(call("GET", UomController.UOMS_PATH, admin, null, null), HttpStatus.OK);
            assertThat(direct.toString()).contains(changed);
            assertThat(response.get("answer").asString()).contains(changed).doesNotContain("Persisted AI unit");
        } finally { jdbc.update("UPDATE wms_uoms SET description = ? WHERE id = ?", "Persisted AI unit", ownedRows.get("wms_uoms")); }
    }
    @Test void aiOnlyPermissionHasEmptyBusinessToolsAndDoesNotInventState() throws Exception {
        permission.aiOnly.add(normalId);
        var response = checked(invoke(normal, body(MULTIPLE_READ_REQUEST)), HttpStatus.OK);
        assertThat(response.get("toolsUsed").size()).isZero();
        assertThat(response.get("answer").asString()).contains("cannot obtain").doesNotContain(UOM_CODE, STORAGE_CODE, FAMILY_CODE);
        assertThat(discovery.effective(accounts.findUserById(normalId).orElseThrow())).isEmpty();
        assertThat(coreAudit.entries).isEmpty();
    }
    @Test void disabledWmsToolsDisappearAndRestoreWithoutRestart() throws Exception {
        patchModule(WmsModule.ID, false);
        try {
            var response = checked(invoke(admin, body(MULTIPLE_READ_REQUEST)), HttpStatus.OK);
            assertThat(response.get("toolsUsed").size()).isZero();
            assertThat(response.get("answer").asString()).contains("cannot obtain").doesNotContain(UOM_CODE, STORAGE_CODE, FAMILY_CODE);
        } finally { patchModule(WmsModule.ID, true); }
        assertThat(checked(invoke(admin, body(ONE_READ_REQUEST)), HttpStatus.OK).get("toolsUsed").size()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"module", "demotion", "disabled-user"})
    void authorityLostWithinSameRequestIsReevaluatedByCore(String change) throws Exception {
        provider.beforeTool.set(() -> {
            switch (change) {
                case "module" -> states.disable(WmsModule.ID);
                case "demotion" -> jdbc.update("UPDATE auth_user SET admin = false WHERE id = ?", adminId);
                case "disabled-user" -> jdbc.update("UPDATE auth_user SET enabled = false WHERE id = ?", adminId);
                default -> throw new AssertionError();
            }
        });
        try {
            assertError(invoke(admin, body(ONE_READ_REQUEST)), HttpStatus.INTERNAL_SERVER_ERROR, AssistantInvocation.FAILED);
            assertThat(coreAudit.entries).hasSize(1);
            assertThat(coreAudit.entries.getFirst().outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.denied);
            assertThat(aiAudit.entries.getLast().toolsUsed()).isEmpty();
        } finally {
            jdbc.update("UPDATE auth_user SET admin = true, enabled = true WHERE id = ?", adminId);
            states.enable(WmsModule.ID);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"temporary", "malformed", "budget"})
    void providerFailuresHaveOnlySafePublicStatusAndCode(String fault) throws Exception {
        provider.override.set((message, tools, results) -> switch (fault) {
            case "temporary" -> throw new AiAssistantProvider.Unavailable();
            case "malformed" -> new AiAssistantProvider.Answer(null);
            case "budget" -> new AiAssistantProvider.ToolCall(UOMS_LIST, json.createObjectNode());
            default -> throw new AssertionError();
        });
        assertError(invoke(admin, body(ONE_READ_REQUEST)), fault.equals("temporary") ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR, fault.equals("temporary") ? AssistantInvocation.UNAVAILABLE : AssistantInvocation.FAILED);
    }
    @Test void answersUseNativeLanguageNegotiationAndEnglishFallback() throws Exception {
        var en = checked(call("POST", AssistantController.PATH, admin, body(GREETING_REQUEST), "en"), HttpStatus.OK);
        var es = checked(call("POST", AssistantController.PATH, admin, body(GREETING_REQUEST), "es-ES"), HttpStatus.OK);
        var fr = checked(call("POST", AssistantController.PATH, admin, body(GREETING_REQUEST), "fr"), HttpStatus.OK);
        assertThat(es.get("answer").asString()).isNotEqualTo(en.get("answer").asString());
        assertThat(fr.get("answer")).isEqualTo(en.get("answer"));
    }
    private void patchModule(String id, boolean enabled) throws Exception {
        checked(call("PATCH", ModuleController.MODULES_PATH + "/" + id, admin,
                json.writeValueAsString(Map.of(ModuleController.ENABLED_FIELD, enabled)), null), HttpStatus.OK);
    }
    private void assertError(HttpResponse<String> response, HttpStatus status, String code) {
        assertThat(checked(response, status)).isEqualTo(json.valueToTree(com.alxarafe.erbas.adapter.http.ContractError.of(code)));
    }
    private String body(String message) { return json.writeValueAsString(Map.of(AssistantJson.MESSAGE, message)); }
    private String login(String email) throws Exception {
        return checked(call("POST", LOGIN, null, json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null),
                HttpStatus.OK).get("accessToken").asString();
    }
    private HttpResponse<String> invoke(String token, String raw) throws Exception { return call("POST", AssistantController.PATH, token, raw, null); }
    private HttpResponse<String> call(String method, String path, String token, String body, String language) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(TIMEOUT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (language != null) request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode checked(HttpResponse<String> response, HttpStatus status) {
        assertThat(response.statusCode()).isEqualTo(status.value());
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).isEmpty();
        if (status == HttpStatus.UNAUTHORIZED) assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
        else assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        return json.readTree(response.body());
    }
    private record Catalogue(String path, String table, String code, String textField, String label) { }
    static final class RecordingAiAudit implements AiAssistantAuditSink {
        final List<Entry> entries = new CopyOnWriteArrayList<>();
        public void record(Entry entry) { entries.add(entry); }
    }
    static final class RecordingCoreAudit implements CapabilityInvocationAuditSink {
        final List<Entry> entries = new CopyOnWriteArrayList<>();
        public void record(Entry entry) { entries.add(entry); }
    }
    static final class TestPermission implements AiAssistantPermission {
        final Set<Long> aiOnly = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AiAssistantPermission bridge = new AiConfiguration().aiAssistantPermission();
        public boolean granted(com.alxarafe.erbas.auth.application.UserIdentity user) {
            return bridge.granted(user) || user != null && user.enabled() && aiOnly.contains(user.userId());
        }
    }
    static final class ScriptedProvider implements AiAssistantProvider {
        final AtomicReference<AiAssistantProvider> override = new AtomicReference<>();
        final AtomicReference<Runnable> beforeTool = new AtomicReference<>();
        final AiAssistantProvider delegate;
        ScriptedProvider(MessageSource messages) { delegate = new DeterministicAssistantProvider(messages); }
        public Turn respond(String message, List<Capability> tools, List<ToolResult> results) {
            var active = override.get();
            var turn = (active == null ? delegate : active).respond(message, tools, results);
            if (turn instanceof ToolCall) {
                var mutation = beforeTool.getAndSet(null);
                if (mutation != null) mutation.run();
            }
            return turn;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary RecordingAiAudit recordingAiAudit() { return new RecordingAiAudit(); }
        @Bean @Primary RecordingCoreAudit recordingCoreAudit() { return new RecordingCoreAudit(); }
        @Bean @Primary TestPermission testPermission() { return new TestPermission(); }
        @Bean @Primary ScriptedProvider scriptedProvider(MessageSource messages) { return new ScriptedProvider(messages); }
    }
}
