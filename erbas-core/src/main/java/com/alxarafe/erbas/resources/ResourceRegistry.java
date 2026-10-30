package com.alxarafe.erbas.resources;

import java.util.*;
import com.alxarafe.erbas.modularity.ModuleRegistry;

public final class ResourceRegistry {
    @FunctionalInterface public interface Source { List<ResourceDefinition> resources(); }
    private final Map<String, ResourceDefinition> definitions;
    private final Map<String, ResourceExecutionAdapter> adapters;
    private final Map<String, Map<String, ResourceActionExecutor>> actions;
    public ResourceRegistry(List<Source> sources, List<ResourceExecutionAdapter> executors,
            List<ResourceActionExecutor> actionExecutors, ModuleRegistry modules) {
        var owners = modules.modules().stream().map(module -> module.descriptor().id()).toList();
        var declared = new TreeMap<String, ResourceDefinition>();
        for (var source : sources) for (var definition : source.resources()) {
            if (!owners.contains(definition.moduleId()) || declared.putIfAbsent(definition.id(), definition) != null)
                throw new IllegalArgumentException("Unknown module or duplicate resource");
        }
        for (var definition : declared.values()) for (var relation : definition.relations()) {
            var target = declared.get(relation.targetResource());
            if (target == null) throw new IllegalArgumentException("Unknown relation target");
            try {
                var local = definition.field(relation.localField()); var foreign = target.field(relation.targetField());
                if (local.type() != foreign.type() || relation.required() && local.nullable()) throw new IllegalArgumentException("Invalid relation fields");
                if (relation.displayField() != null) target.field(relation.displayField());
            } catch (ResourceFailure invalid) { throw new IllegalArgumentException("Invalid relation fields"); }
        }
        var bound = new HashMap<String, ResourceExecutionAdapter>();
        for (var executor : executors) if (!declared.containsKey(executor.resourceId()) || bound.putIfAbsent(executor.resourceId(), executor) != null)
            throw new IllegalArgumentException("Invalid or duplicate resource adapter");
        var actionBindings = new HashMap<String, Map<String, ResourceActionExecutor>>();
        for (var executor : actionExecutors) {
            var definition = declared.get(executor.resourceId());
            if (definition == null || definition.actions().stream().noneMatch(action -> action.name().equals(executor.actionName()))
                    || actionBindings.computeIfAbsent(executor.resourceId(), key -> new HashMap<>()).putIfAbsent(executor.actionName(), executor) != null)
                throw new IllegalArgumentException("Invalid or duplicate action executor");
        }
        definitions = Collections.unmodifiableMap(declared); adapters = Map.copyOf(bound);
        var frozenActions = new HashMap<String, Map<String, ResourceActionExecutor>>();
        actionBindings.forEach((key, value) -> frozenActions.put(key, Map.copyOf(value))); actions = Map.copyOf(frozenActions);
    }
    public List<ResourceDefinition> definitions() { return List.copyOf(definitions.values()); }
    public ResourceDefinition definition(String id) {
        var definition = definitions.get(id);
        if (definition == null) throw new ResourceFailure(ResourceFailure.Code.resource_not_found);
        return definition;
    }
    public ResourceExecutionAdapter adapter(String id) {
        var adapter = adapters.get(id);
        if (adapter == null) throw new ResourceFailure(ResourceFailure.Code.resource_execution_unavailable);
        return adapter;
    }
    public ResourceActionExecutor action(String resource, String name) {
        var executor = actions.getOrDefault(resource, Map.of()).get(name);
        if (executor == null) throw new ResourceFailure(ResourceFailure.Code.resource_execution_unavailable);
        return executor;
    }
}
