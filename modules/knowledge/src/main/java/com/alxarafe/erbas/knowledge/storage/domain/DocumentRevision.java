package com.alxarafe.erbas.knowledge.storage.domain;

import java.time.Instant;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.DocumentReference;

/** Authorization metadata is a snapshot, never an authorization grant. */
public record DocumentRevision(UUID id, UUID documentId, int number, String provenance,
        String createdBy, Instant createdAt, DocumentReference reference, DocumentContent content) {
    public static final int FIRST_NUMBER = 1;
}
