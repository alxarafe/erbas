package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Optional;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.DocumentReference;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentRevision;

/** Trusted persistence boundary, never a caller-facing authorization API. */
public interface DocumentReadStore {
    Optional<Integer> latestRevision(UUID documentId);
    Optional<DocumentReference> reference(UUID documentId, int number);
    Optional<DocumentRevision> revision(UUID documentId, int number);
}
