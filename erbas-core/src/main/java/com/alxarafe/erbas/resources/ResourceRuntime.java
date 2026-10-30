package com.alxarafe.erbas.resources;

import java.util.*;
import java.util.function.LongFunction;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import tools.jackson.databind.JsonNode;
import static com.alxarafe.erbas.resources.ResourceFailure.Code.*;

/** Protocol orchestration; adapters own business rules and persistence. No HTTP, SQL or module knowledge. */
public final class ResourceRuntime {
    private final ResourceRegistry registry;
    private final ModuleStateService modules;
    private final LongFunction<Optional<UserIdentity>> users;
    private final List<FunctionalPermissionPolicy> policies;
    public ResourceRuntime(ResourceRegistry registry, ModuleStateService modules, LongFunction<Optional<UserIdentity>> users,
            List<FunctionalPermissionPolicy> policies) {
        this.registry = registry; this.modules = modules; this.users = users; this.policies = List.copyOf(policies);
    }
    public UserIdentity current(UserIdentity principal) {
        if (principal == null) throw new ResourceFailure(unauthorized);
        return users.apply(principal.userId()).filter(user -> user.userId() == principal.userId() && user.enabled())
                .orElseThrow(() -> new ResourceFailure(unauthorized));
    }
    public boolean enabled(ResourceDefinition definition) {
        try { return modules.isEnabled(definition.moduleId()); } catch (ModuleUnavailableException unavailable) { return false; }
    }
    public boolean authorized(UserIdentity user, String permission) {
        return permission != null && policies.stream().anyMatch(policy -> policy.granted(user, permission));
    }
    public void moduleGuard(ResourceDefinition definition) { if (!enabled(definition)) throw new ResourceFailure(module_disabled); }
    public void contextGuard(ResourceExecutionAdapter adapter, UserIdentity user) { if (!adapter.contextAllowed(user)) throw new ResourceFailure(forbidden); }
    public boolean listable(UserIdentity principal, ResourceDefinition definition) {
        var user = current(principal);
        if (!enabled(definition) || !authorized(user, definition.operations().get(ResourceOperation.list))) return false;
        try { return registry.adapter(definition.id()).contextAllowed(user); }
        catch (ResourceFailure unavailable) { return false; }
    }
    public Object execute(UserIdentity principal, ResourceDefinition definition, ResourceOperation operation, Object id,
            JsonNode body, ResourceQuery.Transport query) {
        var adapter = registry.adapter(definition.id());
        if (!definition.operations().containsKey(operation)) throw new ResourceFailure(resource_operation_not_supported);
        moduleGuard(definition);
        var user = current(principal); contextGuard(adapter, user);
        if (!authorized(user, definition.operations().get(operation))) throw new ResourceFailure(forbidden);
        return switch (operation) {
            case list -> {
                var typedQuery = ResourceQuery.validate(definition, query);
                var page = adapter.list(user, typedQuery);
                if (page == null || page.items() == null || !typedQuery.offset().equals(page.offset()) || typedQuery.limit() != page.limit()
                        || page.items().size() > page.limit() || page.total() != null && page.total().signum() < 0)
                    throw new ResourceFailure(resource_execution_failed);
                var output = new LinkedHashMap<String, Object>();
                var rows = page.items().stream().map(row -> output(definition, row)).toList();
                if (page.total() != null && page.total().compareTo(java.math.BigInteger.valueOf(rows.size())) < 0) throw new ResourceFailure(resource_execution_failed);
                output.put("items", rows); output.put("offset", page.offset()); output.put("limit", page.limit());
                if (page.total() != null) output.put("total", page.total());
                yield output;
            }
            case get -> identifiedOutput(definition, adapter.get(user, id).orElseThrow(() -> new ResourceFailure(resource_not_found)), id);
            case create -> output(definition, adapter.create(user, ResourceRecords.input(definition.fields(), body, true)));
            case update -> {
                var change = ResourceRecords.input(definition.fields(), body, false);
                if (change.containsKey(definition.idField()) && !Objects.equals(change.get(definition.idField()), id)) throw ResourceFailure.invalid();
                for (var field : definition.fields()) if (change.containsKey(field.name()) && field.immutable()) throw new ResourceFailure(resource_conflict);
                var existing = adapter.get(user, id).orElseThrow(() -> new ResourceFailure(resource_not_found));
                identifiedOutput(definition, existing, id);
                var merged = new LinkedHashMap<>(existing); merged.putAll(change);
                var valid = ResourceRecords.validate(definition, merged);
                yield identifiedOutput(definition, adapter.update(user, id, valid), id);
            }
            case delete -> { if (!adapter.delete(user, id)) throw new ResourceFailure(resource_not_found); yield null; }
        };
    }
    private JsonNode output(ResourceDefinition definition, Map<String, Object> row) {
        try { return ResourceRecords.json(definition, row); }
        catch (RuntimeException invalid) { throw new ResourceFailure(resource_execution_failed); }
    }
    private JsonNode identifiedOutput(ResourceDefinition definition, Map<String, Object> row, Object expected) {
        var output = output(definition, row);
        if (!Objects.equals(definition.field(definition.idField()).value(output.get(definition.idField()), false), expected))
            throw new ResourceFailure(resource_execution_failed);
        return output;
    }
    public Object identifier(ResourceDefinition definition, String text) {
        var type = definition.field(definition.idField()).type();
        try { return type.parse(type.integer() ? ResourceJson.JSON.readTree(text) : ResourceJson.JSON.valueToTree(text)); }
        catch (RuntimeException invalid) { throw ResourceFailure.invalid(); }
    }
}
