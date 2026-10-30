package com.alxarafe.erbas.resources;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResourceActionsTests {
    private static final UserIdentity USER = new UserIdentity(7, "fixture@example.test", true, true);
    private static final String ACTION_PERMISSION = "test.action.execute";
    private static final String ACTION = "test-action";
    private static final String ID = "abcdefab-1234-1234-1234-abcdefabcdef";
    @ParameterizedTest @EnumSource(ResourceActionDefinition.Scope.class)
    void everyScopeHasTypedTargetsParametersDefaultsAndRecheckedAvailability(ResourceActionDefinition.Scope scope) {
        var base = ResourceConformanceConfiguration.definition();
        var parameter = new FieldDefinition("flag", ScalarType.BOOLEAN, false, true, false, false,
                ResourceJson.JSON.valueToTree(true), FieldDefinition.Constraints.NONE, FieldDefinition.Capabilities.NONE, List.of(), List.of());
        var action = new ResourceActionDefinition(ACTION, scope, scope == ResourceActionDefinition.Scope.SELECTION, ACTION_PERMISSION, List.of(parameter), List.of());
        var definition = new ResourceDefinition(base.id(), base.version(), base.moduleId(), base.idField(), base.fields(), base.operations(),
                List.of(), List.of(action), List.of(), null);
        var available = new AtomicBoolean(true); var authorized = new AtomicBoolean(true); var calls = new ArrayList<Map<String, Object>>();
        var executions = new ArrayList<List<Object>>();
        ResourceActionExecutor executor = new ResourceActionExecutor() {
            public String resourceId() { return definition.id(); }
            public String actionName() { return ACTION; }
            public Availability availability(UserIdentity user, List<Object> targets) { calls.add(Map.of()); return new Availability(available.get(), available.get() ? null : "state_changed"); }
            public void execute(UserIdentity user, List<Object> targets, Map<String, Object> parameters) { executions.add(targets); assertThat(parameters.get("flag")).isEqualTo(true); }
        };
        var modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
            public ModuleDescriptor descriptor() { return new ModuleDescriptor(base.moduleId(), ModuleApi.CURRENT_VERSION, List.of()); }
            public void initialize() { }
        })));
        var state = new HashMap<String, Boolean>();
        var states = new ModuleStateService(modules, new ModuleStateStore() {
            public Optional<Boolean> find(String id) { return Optional.ofNullable(state.get(id)); }
            public void update(Consumer<Map<String, Boolean>> update) { update.accept(state); }
        }); states.synchronize();
        var registry = new ResourceRegistry(List.of(() -> List.of(definition)), List.of(new ResourceConformanceConfiguration.ProbeAdapter()), List.of(executor), modules);
        var runtime = new ResourceRuntime(registry, states, id -> Optional.of(USER),
                List.of((user, permission) -> authorized.get() && ACTION_PERMISSION.equals(permission)));
        var service = new ResourceActions(registry, runtime);
        String target = scope == ResourceActionDefinition.Scope.ENTITY ? "\"id\":\"" + ID + "\""
                : scope == ResourceActionDefinition.Scope.SELECTION ? "\"ids\":[\"" + ID + "\"]" : "";
        var availability = ResourceJson.object("{\"scope\":\"" + scope + "\"" + (target.isEmpty() ? "" : "," + target) + "}");
        var input = ResourceJson.object(target.isEmpty() ? "{}" : "{\"target\":{" + target + "}}");
        assertThat(service.availability(USER, definition, availability).getFirst().get("available")).isEqualTo(true);
        service.execute(USER, definition, action, input);
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst()).hasSize(scope == ResourceActionDefinition.Scope.COLLECTION ? 0 : 1);
        available.set(false);
        assertThatThrownBy(() -> service.execute(USER, definition, action, input)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.resource_action_unavailable);
        authorized.set(false); int contextualCalls = calls.size();
        assertThat(service.availability(USER, definition, availability).getFirst()).isEqualTo(Map.of("name", ACTION, "authorized", false, "available", false));
        assertThat(calls).hasSize(contextualCalls);
        assertThatThrownBy(() -> service.execute(USER, definition, action, input)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.forbidden);
        assertThat(executions).hasSize(1);
    }
    @Test void presentationReferencesAndRelationTargetsFailFast() {
        var base = ResourceConformanceConfiguration.definition();
        var presentation = new ResourcePresentation(new ResourcePresentation.Form(new ResourcePresentation.Behavior(ResourcePresentation.UnsavedChanges.CONFIRM_DISCARD),
                List.of("missing"), List.of()));
        assertThatThrownBy(() -> new ResourceDefinition(base.id(), base.version(), base.moduleId(), base.idField(), base.fields(), base.operations(),
                List.of(), base.actions(), List.of(), presentation)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceActionDefinition(ACTION, ResourceActionDefinition.Scope.COLLECTION, true, ACTION_PERMISSION, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void typedSelectionHasFiniteUniqueTargetsAndNoExtraMembers() {
        var service = new ResourceActions(null, null); var definition = ResourceConformanceConfiguration.definition();
        for (String target : List.of("{}", "{\"ids\":[]}", "{\"ids\":[\"" + ID + "\",\"" + ID + "\"]}",
                "{\"ids\":[3]}", "{\"ids\":[\"" + ID + "\"],\"extra\":true}"))
            assertThatThrownBy(() -> service.targets(definition, ResourceActionDefinition.Scope.SELECTION, ResourceJson.object(target)))
                    .isInstanceOf(ResourceFailure.class);
        var ids = ResourceJson.JSON.createArrayNode();
        for (int index = 0; index <= ResourceActions.MAX_SELECTION; index++) ids.add(UUID.randomUUID().toString());
        var target = ResourceJson.JSON.createObjectNode().set("ids", ids);
        assertThatThrownBy(() -> service.targets(definition, ResourceActionDefinition.Scope.SELECTION, target)).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> service.targets(definition, ResourceActionDefinition.Scope.COLLECTION, ResourceJson.object("{}"))).isInstanceOf(ResourceFailure.class);
    }
}
