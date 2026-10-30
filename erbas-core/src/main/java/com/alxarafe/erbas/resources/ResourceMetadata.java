package com.alxarafe.erbas.resources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;

public final class ResourceMetadata {
    public static final String ROOT = "/api/meta/resources";
    private final ResourceRegistry registry;
    private final ResourceRuntime runtime;
    public ResourceMetadata(ResourceRegistry registry, ResourceRuntime runtime) { this.registry = registry; this.runtime = runtime; }
    public List<Map<String, Object>> discover(UserIdentity principal) {
        var user = runtime.current(principal);
        return registry.definitions().stream().filter(runtime::enabled).map(definition -> summary(user, definition)).toList();
    }
    private Map<String, Object> identity(ResourceDefinition definition) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", definition.id()); map.put("version", definition.version()); map.put("moduleId", definition.moduleId());
        // Record JSON alone conflates an absent default with an explicitly declared null.
        var structural = List.of(definition, definition.fields().stream().map(field -> field.defaultValue() != null).toList(),
                definition.actions().stream().map(action -> action.parameters().stream().map(field -> field.defaultValue() != null).toList()).toList());
        map.put("definitionHash", hash(ResourceJson.JSON.writeValueAsString(structural))); return map;
    }
    private Map<String, Object> operations(UserIdentity user, ResourceDefinition definition) {
        var operations = new LinkedHashMap<String, Object>();
        for (var operation : ResourceOperation.values()) {
            boolean declared = definition.operations().containsKey(operation);
            operations.put(operation.name(), Map.of("declared", declared, "authorized", declared && runtime.enabled(definition)
                    && runtime.authorized(user, definition.operations().get(operation))));
        }
        return operations;
    }
    private Map<String, Object> summary(UserIdentity user, ResourceDefinition definition) {
        var summary = identity(definition); summary.put("operations", operations(user, definition));
        summary.put("metadataPath", ROOT + "/" + segment(definition.id())); return summary;
    }
    public Map<String, Object> detail(UserIdentity principal, ResourceDefinition definition) {
        var user = runtime.current(principal);
        var metadata = identity(definition);
        metadata.put("idDefinition", Map.of("field", definition.idField())); metadata.put("moduleEnabled", runtime.enabled(definition));
        metadata.put("fields", definition.fields().stream().map(field -> field.metadata(false)).toList());
        metadata.put("operations", operations(user, definition));
        metadata.put("relations", definition.relations().stream().map(relation -> {
            var map = new LinkedHashMap<String, Object>(); map.put("name", relation.name()); map.put("kind", "MANY_TO_ONE");
            map.put("targetResource", relation.targetResource()); map.put("localField", relation.localField());
            map.put("targetField", relation.targetField()); map.put("displayField", relation.displayField()); map.put("required", relation.required()); return map;
        }).toList());
        metadata.put("actions", definition.actions().stream().map(action -> Map.of("name", action.name(), "scope", action.scope(),
                "requiresSelection", action.requiresSelection(), "authorized", runtime.enabled(definition) && runtime.authorized(user, action.permission()),
                "annotations", action.annotations(), "parameters", action.parameters().stream().map(field -> field.metadata(true)).toList())).toList());
        metadata.put("annotations", definition.annotations());
        if (definition.presentation() != null) metadata.put("presentation", definition.presentation());
        return metadata;
    }
    public static String segment(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    public static String hash(String representation) {
        String canonical = ResourceJson.JSON.writeValueAsString(canonical(ResourceJson.JSON.readTree(representation)));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static tools.jackson.databind.JsonNode canonical(tools.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var ordered = ResourceJson.JSON.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> ordered.set(entry.getKey(), canonical(entry.getValue())));
            return ordered;
        }
        if (value.isArray()) {
            var ordered = ResourceJson.JSON.createArrayNode(); for (var item : value) ordered.add(canonical(item)); return ordered;
        }
        return value;
    }
}
