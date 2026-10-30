package com.alxarafe.erbas.resources;

import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import tools.jackson.databind.JsonNode;
import static com.alxarafe.erbas.resources.ResourceFailure.Code.*;

public final class ResourceActions {
    public static final int MAX_SELECTION = 100;
    private final ResourceRegistry registry;
    private final ResourceRuntime runtime;
    public ResourceActions(ResourceRegistry registry, ResourceRuntime runtime) { this.registry = registry; this.runtime = runtime; }
    public ResourceActionDefinition definition(ResourceDefinition resource, String name) {
        return resource.actions().stream().filter(action -> action.name().equals(name)).findFirst()
                .orElseThrow(() -> new ResourceFailure(resource_action_not_found));
    }
    public List<Object> targets(ResourceDefinition resource, ResourceActionDefinition.Scope scope, JsonNode target) {
        var type = resource.field(resource.idField()).type();
        if (scope == ResourceActionDefinition.Scope.COLLECTION) {
            if (target != null) throw ResourceFailure.invalid(); return List.of();
        }
        if (target == null || !target.isObject() || target.size() != 1) throw ResourceFailure.invalid();
        if (scope == ResourceActionDefinition.Scope.ENTITY) {
            if (!target.has("id")) throw ResourceFailure.invalid(); return List.of(type.parse(target.get("id")));
        }
        if (!target.has("ids") || !target.get("ids").isArray() || target.get("ids").isEmpty()
                || target.get("ids").size() > MAX_SELECTION) throw ResourceFailure.invalid();
        var values = new ArrayList<Object>(); for (var id : target.get("ids")) values.add(type.parse(id));
        if (new HashSet<>(values).size() != values.size()) throw ResourceFailure.invalid(); return List.copyOf(values);
    }
    public void execute(UserIdentity principal, ResourceDefinition resource, ResourceActionDefinition action, JsonNode envelope) {
        if (envelope.properties().stream().anyMatch(entry -> !Set.of("target", "parameters").contains(entry.getKey()))) throw ResourceFailure.invalid();
        var target = targets(resource, action.scope(), envelope.get("target"));
        var rawParameters = envelope.get("parameters");
        if (rawParameters != null && !rawParameters.isObject()) throw ResourceFailure.invalid();
        var executor = registry.action(resource.id(), action.name());
        runtime.moduleGuard(resource); var user = runtime.current(principal); runtime.contextGuard(registry.adapter(resource.id()), user);
        if (!runtime.authorized(user, action.permission())) throw new ResourceFailure(forbidden);
        validateTargets(resource, target);
        var parameters = ResourceRecords.input(action.parameters(), rawParameters == null ? ResourceJson.JSON.createObjectNode() : rawParameters, true);
        var availability = executor.availability(user, target);
        if (availability == null) throw new ResourceFailure(resource_execution_failed);
        if (!availability.available()) throw new ResourceFailure(resource_action_unavailable);
        executor.execute(user, target, parameters);
    }
    public List<Map<String, Object>> availability(UserIdentity principal, ResourceDefinition resource, JsonNode envelope) {
        ResourceActionDefinition.Scope scope;
        try {
            if (!envelope.has("scope") || !envelope.get("scope").isString()) throw ResourceFailure.invalid();
            scope = ResourceActionDefinition.Scope.valueOf(envelope.get("scope").asString());
        } catch (IllegalArgumentException invalid) { throw ResourceFailure.invalid(); }
        var targetNode = envelope.deepCopy(); ((tools.jackson.databind.node.ObjectNode) targetNode).remove("scope");
        var target = targets(resource, scope, scope == ResourceActionDefinition.Scope.COLLECTION && targetNode.isEmpty() ? null : targetNode);
        runtime.moduleGuard(resource); var user = runtime.current(principal); runtime.contextGuard(registry.adapter(resource.id()), user);
        var result = new ArrayList<Map<String, Object>>();
        for (var action : resource.actions()) if (action.scope() == scope) {
            var state = new LinkedHashMap<String, Object>(); state.put("name", action.name());
            boolean authorized = runtime.authorized(user, action.permission()); state.put("authorized", authorized);
            if (!authorized) state.put("available", false);
            else {
                validateTargets(resource, target);
                var availability = registry.action(resource.id(), action.name()).availability(user, target);
                if (availability == null) throw new ResourceFailure(resource_execution_failed);
                state.put("available", availability.available());
                if (availability.unavailableReason() != null) state.put("unavailableReason", availability.unavailableReason());
            }
            result.add(state);
        }
        return result;
    }
    private void validateTargets(ResourceDefinition resource, List<Object> target) {
        var field = resource.field(resource.idField());
        for (var id : target) field.value(field.type().json(id), false);
    }
}
