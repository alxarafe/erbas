package com.alxarafe.erbas.read;

import java.util.List;
import java.util.regex.Pattern;
import com.alxarafe.erbas.capabilities.Capability;
import tools.jackson.databind.JsonNode;

/** Internal module declaration, not a capability or a public HTTP resource. */
public record ReadPrimitive(String id, String module, String name, String description, String effect,
        JsonNode inputSchema, List<String> requiredPermissions) {
    private static final Pattern ID = Pattern.compile(
            "[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    public ReadPrimitive {
        if (!validId(id) || module == null || module.isBlank() || name == null || name.isBlank()
                || description == null || description.isBlank() || !Capability.READ.equals(effect)
                || inputSchema == null || !inputSchema.isObject() || requiredPermissions == null || requiredPermissions.isEmpty()
                || requiredPermissions.stream().anyMatch(permission -> permission == null || permission.isBlank())) {
            throw new IllegalArgumentException("Invalid READ primitive declaration");
        }
        requiredPermissions = List.copyOf(requiredPermissions);
        if (!requiredPermissions.equals(requiredPermissions.stream().distinct().sorted().toList())) {
            throw new IllegalArgumentException("Primitive permissions must be unique and ordered");
        }
        inputSchema = inputSchema.deepCopy();
    }
    @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
}
