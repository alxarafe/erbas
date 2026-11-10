package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Provider-independent opaque identifier and expected integrity, persisted by Knowledge. */
public record StoredObject(UUID id, String sha256, long size) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public StoredObject {
        Objects.requireNonNull(id);
        if (sha256 == null || !SHA256.matcher(sha256).matches() || size < 0)
            throw new IllegalArgumentException("Invalid stored object integrity");
    }
}
