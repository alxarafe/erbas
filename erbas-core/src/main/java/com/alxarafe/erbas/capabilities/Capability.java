package com.alxarafe.erbas.capabilities;

import java.util.List;
import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Closed CORE-CAP-001 declaration, without execution or grants. */
public record Capability(String id, String module, String version, String name, String description,
        String effect, SchemaReference inputSchema, SchemaReference outputSchema, List<String> requiredPermissions) {
    public static final String READ = "read";
    public static final String WRITE = "write";
    private static final Pattern ID = Pattern.compile(
            "[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    // ECMAScript whitespace used by the published JSON Schema, not Java's ASCII-only \\s.
    private static final Pattern NONBLANK = Pattern.compile(
            "[^\\s\\x{00a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]");

    public Capability {
        if (!validId(id) || !nonblank(module) || version == null || !VERSION.matcher(version).matches()
                || !nonblank(name) || !nonblank(description) || !(READ.equals(effect) || WRITE.equals(effect))
                || inputSchema == null || outputSchema == null || requiredPermissions == null
                || requiredPermissions.stream().anyMatch(permission -> !nonblank(permission))) {
            throw new IllegalArgumentException("Invalid capability declaration");
        }
        requiredPermissions = List.copyOf(requiredPermissions);
        if (!requiredPermissions.equals(requiredPermissions.stream().distinct().sorted().toList())) {
            throw new IllegalArgumentException("Capability permissions must be unique and ordinally ordered");
        }
    }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
    private static boolean nonblank(String value) { return value != null && NONBLANK.matcher(value).find(); }

    public record SchemaReference(@JsonProperty("$ref") String ref) {
        public static final String PREFIX = "#/components/schemas/";
        private static final Pattern REFERENCE = Pattern.compile(Pattern.quote(PREFIX) + "[A-Za-z_][A-Za-z0-9_]*");
        public SchemaReference {
            if (ref == null || !REFERENCE.matcher(ref).matches()) {
                throw new IllegalArgumentException("Expected a local component schema reference");
            }
        }
        public String component() { return ref.substring(PREFIX.length()); }
    }
}
