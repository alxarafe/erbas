package com.alxarafe.erbas.knowledge;

import java.util.Set;

/** Trusted, immutable authorization metadata, never a descriptor accepted from a caller. */
public record DocumentReference(String id, String module, Set<String> requiredPermissions,
        boolean modulePolicyRequired) {
    public DocumentReference {
        if (id == null || id.isBlank() || module == null || module.isBlank()
                || requiredPermissions == null || requiredPermissions.isEmpty()
                || requiredPermissions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Invalid document reference");
        }
        requiredPermissions = Set.copyOf(requiredPermissions);
    }
}
