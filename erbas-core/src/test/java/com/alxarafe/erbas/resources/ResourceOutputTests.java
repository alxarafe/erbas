package com.alxarafe.erbas.resources;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ResourceOutputTests {
    private static final UserIdentity USER = new UserIdentity(7, "fixture@example.test", true, true);
    private final ResourceDefinition definition = ResourceConformanceConfiguration.definition();
    private final UUID id = UUID.randomUUID();
    private final Map<String, Object> row = new LinkedHashMap<>(Map.of("id", id, "code", "ONE", "name", "First", "active", true));
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(definition.moduleId(), ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private boolean context = true;
    private final ResourceExecutionAdapter adapter = new ResourceExecutionAdapter() {
        public String resourceId() { return definition.id(); }
        public boolean contextAllowed(UserIdentity user) { return context; }
        public ResourcePage list(UserIdentity user, ResourceQuery query) { return new ResourcePage(List.of(row), query.offset(), query.limit(), null); }
        public Optional<Map<String, Object>> get(UserIdentity user, Object ignored) { return Optional.of(row); }
        public Map<String, Object> create(UserIdentity user, Map<String, Object> input) { return row; }
        public Map<String, Object> update(UserIdentity user, Object ignored, Map<String, Object> merged) { return row; }
        public boolean delete(UserIdentity user, Object ignored) { return true; }
    };
    private ResourceRegistry registry(List<ResourceDefinition> definitions, List<ResourceExecutionAdapter> adapters) {
        return new ResourceRegistry(List.of(() -> definitions), adapters, List.of(), modules);
    }
    private ResourceRuntime runtime(ResourceRegistry registry) {
        return runtime(registry, true);
    }
    private ResourceRuntime runtime(ResourceRegistry registry, boolean granted) {
        var state = new HashMap<String, Boolean>();
        var states = new ModuleStateService(modules, new ModuleStateStore() {
            public Optional<Boolean> find(String id) { return Optional.ofNullable(state.get(id)); }
            public void update(Consumer<Map<String, Boolean>> change) { change.accept(state); }
        }); states.synchronize();
        return new ResourceRuntime(registry, states, ignored -> Optional.of(USER), List.of((user, permission) -> granted));
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "unknown", "wrong-type", "wrong-id"})
    void knownInvalidRecordOutputCannotBecomeSuccessful(String fault) {
        switch (fault) {
            case "missing" -> row.remove("name");
            case "unknown" -> row.put("hidden", "private-provider-output");
            case "wrong-type" -> row.put("active", "true");
            case "wrong-id" -> row.put("id", UUID.randomUUID());
            default -> throw new AssertionError();
        }
        var runtime = runtime(registry(List.of(definition), List.of(adapter)));
        assertThatThrownBy(() -> runtime.execute(USER, definition, ResourceOperation.get, id, null, null))
                .isInstanceOf(ResourceFailure.class).extracting(failure -> ((ResourceFailure) failure).code())
                .isEqualTo(ResourceFailure.Code.resource_execution_failed);
    }
    @Test void optionalTotalIsOmittedAndContextDoesNotHideMetadata() {
        var registry = registry(List.of(definition), List.of(adapter)); var runtime = runtime(registry);
        var page = (Map<?, ?>) runtime.execute(USER, definition, ResourceOperation.list, null, null, ResourceQuery.transport(Map.of()));
        assertThat(page.containsKey("total")).isFalse(); assertThat(page.containsKey("order")).isFalse();
        context = false;
        assertThat(runtime.listable(USER, definition)).isFalse();
        assertThat(new ResourceMetadata(registry, runtime).detail(USER, definition)).containsEntry("moduleEnabled", true);
        assertThatThrownBy(() -> runtime.execute(USER, definition, ResourceOperation.get, id, null, null))
                .isInstanceOf(ResourceFailure.class).extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.forbidden);
    }
    @Test void missingAdapterAndDuplicateAdapterFailExplicitly() {
        var registry = registry(List.of(definition), List.of());
        assertThatThrownBy(() -> runtime(registry).execute(USER, definition, ResourceOperation.get, id, null, null))
                .isInstanceOf(ResourceFailure.class).extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.resource_execution_unavailable);
        assertThatThrownBy(() -> registry(List.of(definition), List.of(adapter, adapter))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void representationHashesCanonicalizeObjectKeysButPreserveArrayOrder() {
        assertThat(ResourceMetadata.hash("{\"a\":1,\"b\":2}")).isEqualTo(ResourceMetadata.hash("{\"b\":2,\"a\":1}"));
        assertThat(ResourceMetadata.hash("[1,2]")).isNotEqualTo(ResourceMetadata.hash("[2,1]"));
    }
    @Test void definitionHashDistinguishesAbsentDefaultFromExplicitNullDefault() {
        var fields = definition.fields().stream().map(field -> field.name().equals("note")
                ? new FieldDefinition(field.name(), field.type(), true, field.required(), field.readOnly(), field.immutable(),
                        ResourceJson.JSON.nullNode(), field.constraints(), field.capabilities(), field.enumValues(), field.annotations()) : field).toList();
        var changed = new ResourceDefinition(definition.id(), definition.version(), definition.moduleId(), definition.idField(), fields,
                definition.operations(), definition.relations(), definition.actions(), definition.annotations(), definition.presentation());
        var originalRegistry = registry(List.of(definition), List.of(adapter));
        var changedRegistry = registry(List.of(changed), List.of(adapter));
        assertThat(new ResourceMetadata(originalRegistry, runtime(originalRegistry)).detail(USER, definition).get("definitionHash"))
                .isNotEqualTo(new ResourceMetadata(changedRegistry, runtime(changedRegistry)).detail(USER, changed).get("definitionHash"));
    }
    @Test void validRelationsAnnotationsAndPresentationAreProjectedWithoutAuthority() {
        var annotation = new FieldDefinition.Annotation("Unknown", "GrantEverything", ResourceJson.JSON.valueToTree(true));
        var presentation = new ResourcePresentation(new ResourcePresentation.Form(
                new ResourcePresentation.Behavior(ResourcePresentation.UnsavedChanges.ALLOW_DISCARD), List.of(ResourceConformanceConfiguration.ACTION),
                List.of(new ResourcePresentation.Tab("main", null, List.of(), List.of(new ResourcePresentation.Section("section", null, false, false, List.of(),
                        List.of(new ResourcePresentation.Group("group", null, List.of("code", "name")))))))));
        var relation = new ResourceDefinition.RelationDefinition("self", definition.id(), "id", "id", "name", true);
        var declared = new ResourceDefinition(definition.id(), definition.version(), definition.moduleId(), definition.idField(), definition.fields(),
                definition.operations(), List.of(relation), definition.actions(), List.of(annotation), presentation);
        var registry = registry(List.of(declared), List.of(adapter));
        var metadata = new ResourceMetadata(registry, runtime(registry)).detail(USER, declared);
        var json = ResourceJson.JSON.valueToTree(metadata);
        assertThat(json.get("relations").get(0).get("kind").asString()).isEqualTo("MANY_TO_ONE");
        assertThat(json.get("presentation").get("form").get("tabs").get(0).has("label")).isFalse();
        var denied = ResourceJson.JSON.valueToTree(new ResourceMetadata(registry, runtime(registry, false)).detail(USER, declared));
        assertThat(denied.get("operations").get("create").get("authorized").asBoolean()).isFalse();
        var invalidRelation = new ResourceDefinition.RelationDefinition("bad", definition.id(), "id", "missing", null, true);
        var invalid = new ResourceDefinition(definition.id(), definition.version(), definition.moduleId(), definition.idField(), definition.fields(),
                definition.operations(), List.of(invalidRelation), definition.actions(), List.of(), null);
        assertThatThrownBy(() -> registry(List.of(invalid), List.of(adapter))).isInstanceOf(IllegalArgumentException.class);
    }
}
