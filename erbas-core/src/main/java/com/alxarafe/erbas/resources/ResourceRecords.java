package com.alxarafe.erbas.resources;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class ResourceRecords {
    private ResourceRecords() { }
    public static JsonNode transport(String body) {
        var node = ResourceJson.object(body);
        for (var property : node.properties()) if (property.getValue().isContainer() || !ResourceJson.validText(property.getKey())) throw ResourceFailure.invalid();
        return node;
    }
    public static Map<String, Object> input(List<FieldDefinition> fields, JsonNode node, boolean create) {
        var result = new LinkedHashMap<String, Object>();
        for (var property : node.properties()) {
            var field = fields.stream().filter(value -> value.name().equals(property.getKey())).findFirst().orElseThrow(ResourceFailure::invalid);
            // Structural/type validation precedes immutable conflicts. Read-only inputs remain invalid.
            Object value = field.value(property.getValue(), !create && field.immutable());
            if (field.readOnly()) throw ResourceFailure.invalid();
            result.put(field.name(), value);
        }
        if (create) {
            for (var field : fields) {
                if (!result.containsKey(field.name()) && !field.readOnly() && field.defaultValue() != null)
                    result.put(field.name(), field.value(field.defaultValue(), false));
                if (field.required() && !field.readOnly() && !result.containsKey(field.name())) throw ResourceFailure.invalid();
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, Object> validate(ResourceDefinition definition, Map<String, Object> record) {
        if (record == null || record.keySet().stream().anyMatch(name -> definition.fields().stream().noneMatch(field -> field.name().equals(name))))
            throw ResourceFailure.invalid();
        var normalized = new LinkedHashMap<String, Object>();
        for (var field : definition.fields()) {
            if (!record.containsKey(field.name())) {
                if (field.required() || field.name().equals(definition.idField())) throw ResourceFailure.invalid();
            } else normalized.put(field.name(), field.value(field.type().json(record.get(field.name())), false));
        }
        return Collections.unmodifiableMap(normalized);
    }
    public static ObjectNode json(ResourceDefinition definition, Map<String, Object> record) {
        var output = ResourceJson.JSON.createObjectNode();
        validate(definition, record).forEach((name, value) -> output.set(name, definition.field(name).type().json(value)));
        return output;
    }
}
