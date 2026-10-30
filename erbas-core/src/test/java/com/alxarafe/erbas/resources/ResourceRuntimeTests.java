package com.alxarafe.erbas.resources;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.http.*;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.resources.ResourceOperation.*;
import static com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration.*;

class ResourceRuntimeTests {
    private static final UserIdentity ADMIN = new UserIdentity(7, "admin@example.test", true, true);
    private static final UserIdentity NORMAL = new UserIdentity(8, "normal@example.test", true, false);
    private static final String CREATE = "{\"code\":\"ONE\",\"name\":\"First\"}";
    private final ResourceDefinition definition = ResourceConformanceConfiguration.definition();
    private final Map<Long, UserIdentity> users = new HashMap<>(Map.of(ADMIN.userId(), ADMIN, NORMAL.userId(), NORMAL));
    private final Map<String, Boolean> persisted = new HashMap<>();
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(ConformanceModulesConfiguration.MATH_ID, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persisted.get(id)); }
        public void update(Consumer<Map<String, Boolean>> update) { update.accept(persisted); }
    });
    private final ProbeAdapter adapter = new ProbeAdapter();
    private final ResourceRegistry registry = registry(definition, List.of(adapter), List.of());
    private boolean permission = true;
    private final ResourceRuntime runtime = runtime(registry);
    private final ResourceMetadata metadata = new ResourceMetadata(registry, runtime);
    private final ResourceController controller = new ResourceController(registry, runtime, metadata, new ResourceActions(registry, runtime));
    ResourceRuntimeTests() { states.synchronize(); }
    private ResourceRegistry registry(ResourceDefinition declaration, List<ResourceExecutionAdapter> adapters, List<ResourceActionExecutor> actions) {
        return new ResourceRegistry(List.of(() -> List.of(declaration)), adapters, actions, modules);
    }
    private ResourceRuntime runtime(ResourceRegistry registry) {
        return new ResourceRuntime(registry, states, id -> Optional.ofNullable(users.get(id)),
                List.of((user, required) -> user.admin() && permission && PERMISSION.equals(required)));
    }
    private Object execute(ResourceOperation operation, Object id, String body) {
        return runtime.execute(ADMIN, definition, operation, id, body == null ? null : ResourceRecords.transport(body),
                ResourceQuery.transport(Map.of()));
    }
    private JsonNode create() { return (JsonNode) execute(create, null, CREATE); }
    private Object id(JsonNode row) { return runtime.identifier(definition, row.get("id").asString()); }
    @Test void registryRejectsDuplicatesAndInvalidDeclarations() {
        assertThatThrownBy(() -> new ResourceRegistry(List.of(() -> List.of(definition, definition)), List.of(), List.of(), modules))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceDefinition(RESOURCE, "1", definition.moduleId(), "missing", definition.fields(),
                definition.operations(), List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        var repeated = new ArrayList<>(definition.fields()); repeated.add(repeated.getFirst());
        assertThatThrownBy(() -> new ResourceDefinition(RESOURCE, "1", definition.moduleId(), "id", repeated,
                definition.operations(), List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceDefinition(RESOURCE, "1", definition.moduleId(), "id", definition.fields(),
                definition.operations(), List.of(), List.of(definition.actions().getFirst(), definition.actions().getFirst()), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void createDefaultsOnlyAbsentFieldsAndReturnsPersistedLocation() {
        var response = controller.records(ADMIN, RESOURCE, null, request("POST", CREATE));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var row = (JsonNode) response.getBody(); assertThat(row.get("active").asBoolean()).isTrue();
        assertThat(row.get("note").isNull()).isTrue();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo(ResourceController.RECORDS + "/" + RESOURCE + "/" + row.get("id").asString());
        assertThatThrownBy(() -> execute(create, null, "{\"code\":\"TWO\",\"name\":\"Second\",\"active\":null}"))
                .isInstanceOf(ResourceFailure.class).extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.invalid_request);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"code\":\"ONE\"}", "{\"code\":true,\"name\":\"First\"}",
            "{\"unknown\":true}", "{\"id\":\"abcdefab-1234-1234-1234-abcdefabcdef\",\"code\":\"ONE\",\"name\":\"First\"}"})
    void createRejectsRequiredReadonlyUnknownAndWrongTypes(String body) { assertThatThrownBy(() -> execute(create, null, body)).isInstanceOf(ResourceFailure.class); }
    @Test void patchPreservesPresenceAndDoesNotReapplyDefault() {
        var initial = create(); var id = id(initial);
        var changed = (JsonNode) execute(update, id, "{\"name\":\"Edited\",\"active\":false,\"note\":\"value\"}");
        assertThat(changed.get("active").asBoolean()).isFalse();
        var cleared = (JsonNode) execute(update, id, "{\"note\":null}");
        assertThat(cleared.get("note").isNull()).isTrue(); assertThat(cleared.get("name").asString()).isEqualTo("Edited");
        assertThat(cleared.get("active").asBoolean()).isFalse();
        assertThat(execute(get, id, null)).isEqualTo(cleared);
    }
    @Test void immutableValidEvenUnchangedConflictsWhileMalformedAndIdAreInvalid() {
        var initial = create(); var id = id(initial);
        for (String body : List.of("{\"code\":\"ONE\"}", "{\"code\":\"OTHER\"}"))
            assertThatThrownBy(() -> execute(update, id, body)).isInstanceOf(ResourceFailure.class)
                    .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.resource_conflict);
        for (String body : List.of("{\"code\":1}", "{\"active\":null}", "{\"id\":\"" + initial.get("id").asString() + "\"}"))
            assertThatThrownBy(() -> execute(update, id, body)).isInstanceOf(ResourceFailure.class)
                    .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.invalid_request);
    }
    @ParameterizedTest @ValueSource(strings = {"{", "[]", "null", "1", "{\"code\":\"a\",\"code\":\"b\"}", "{} {}", "{\"name\":{}}"})
    void strictTransportIsLimitedToRuntime(String raw) { assertThatThrownBy(() -> ResourceRecords.transport(raw)).isInstanceOf(ResourceFailure.class); }
    @Test void genericPrecedenceIsAuthenticationDefinitionTransportOperationModulePermissionValidation() {
        assertFailure(null, "missing", "POST", "{", ResourceFailure.Code.unauthorized);
        assertFailure(NORMAL, "missing", "POST", "{", ResourceFailure.Code.resource_not_found);
        assertFailure(NORMAL, RESOURCE, "POST", "{", ResourceFailure.Code.invalid_request);
        states.disable(definition.moduleId());
        assertFailure(NORMAL, RESOURCE, "POST", CREATE, ResourceFailure.Code.module_disabled);
        states.enable(definition.moduleId());
        assertFailure(NORMAL, RESOURCE, "POST", "{\"unknown\":1}", ResourceFailure.Code.forbidden);
    }
    @Test void persistedIdentityAndModuleAreReevaluatedOnEveryCall() {
        create(); users.put(ADMIN.userId(), new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
        assertFailure(ADMIN, RESOURCE, "GET", null, ResourceFailure.Code.forbidden);
        users.put(ADMIN.userId(), new UserIdentity(ADMIN.userId(), ADMIN.email(), false, true));
        assertFailure(ADMIN, RESOURCE, "GET", null, ResourceFailure.Code.unauthorized);
    }
    @Test void metadataDoesNotRequireCrudPermissionAndRecomputesBeforeEtag() {
        var request = request("GET", null);
        var first = controller.detail(NORMAL, RESOURCE, request);
        String etag = first.getHeaders().getETag(); assertThat(etag).isNotBlank();
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("private, no-cache");
        assertThat(ResourceJson.JSON.writeValueAsString(first.getBody())).doesNotContain(PERMISSION, "permission", "policy");
        request.addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(controller.detail(NORMAL, RESOURCE, request).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        states.disable(definition.moduleId());
        var changed = controller.detail(NORMAL, RESOURCE, request);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getHeaders().getETag()).isNotEqualTo(etag);
        assertThat(metadata.discover(NORMAL)).isEmpty();
        assertThat(((Map<?, ?>) changed.getBody()).get("moduleEnabled")).isEqualTo(false);
    }
    @Test void metadataPermissionProjectionChangesEtagAndUnsupportedOperationHasAllow() {
        var first = controller.detail(ADMIN, RESOURCE, request("GET", null));
        var request = request("GET", null); request.addHeader(HttpHeaders.IF_NONE_MATCH, first.getHeaders().getETag());
        permission = false;
        assertThat(controller.detail(ADMIN, RESOURCE, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        request.setAttribute(ResourceController.DEFINITION_ATTRIBUTE, definition); request.setRequestURI(ResourceController.RECORDS + "/" + RESOURCE);
        var error = ResourceErrors.response(new ResourceFailure(ResourceFailure.Code.resource_operation_not_supported), request);
        assertThat(error.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(ResourceErrors.response(new IllegalStateException("secret SQL class"), request).getBody().toString()).doesNotContain("secret SQL class");
    }
    @Test void pagingOptionalTotalAndEmptyDeleteSemanticsRemainSeparateFromLegacyCollection() {
        var row = create();
        var query = ResourceQuery.transport(Map.of("offset", new String[] {"100"}, "limit", new String[] {"1"}));
        var page = (Map<?, ?>) runtime.execute(ADMIN, definition, list, null, null, query);
        assertThat(page.keySet().stream().map(Object::toString)).containsExactlyInAnyOrder("items", "offset", "limit", "total");
        assertThat((List<?>) page.get("items")).isEmpty(); assertThat(page.get("total")).isEqualTo(BigInteger.ONE);
        execute(delete, id(row), null);
        assertThatThrownBy(() -> execute(delete, id(row), null)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.resource_not_found);
    }
    private void assertFailure(UserIdentity user, String resource, String method, String body, ResourceFailure.Code expected) {
        assertThatThrownBy(() -> controller.records(user, resource, null, request(method, body))).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(expected);
    }
    private MockHttpServletRequest request(String method, String body) {
        var request = new MockHttpServletRequest(method, ResourceController.RECORDS + "/" + RESOURCE);
        if (body != null) request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return request;
    }
}
