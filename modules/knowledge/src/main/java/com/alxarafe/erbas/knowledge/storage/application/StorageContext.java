package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Objects;
import java.util.UUID;

/** Trusted placement boundary, not an authorization grant or a caller-supplied path. */
public record StorageContext(String module, UUID scope) {
    public StorageContext {
        if (module == null || module.isBlank()) throw new IllegalArgumentException("Storage owner is required");
        Objects.requireNonNull(scope);
    }
}
