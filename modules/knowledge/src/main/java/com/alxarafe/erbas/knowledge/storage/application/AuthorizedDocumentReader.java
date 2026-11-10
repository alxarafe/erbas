package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.DocumentAuthorization;
import com.alxarafe.erbas.knowledge.DocumentReference;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentRevision;

/** Only authorized values cross this boundary; callers cannot supply authorization metadata. */
public final class AuthorizedDocumentReader {
    private final DocumentReadStore store;
    private final DocumentAuthorization authorization;

    public AuthorizedDocumentReader(DocumentReadStore store, DocumentAuthorization authorization) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public Optional<DocumentRevision> document(long subject, String owner, UUID id) {
        return read(subject, owner, id, null);
    }

    public Optional<DocumentRevision> revision(long subject, String owner, UUID id, int number) {
        if (number < DocumentRevision.FIRST_NUMBER) return Optional.empty();
        return read(subject, owner, id, number);
    }

    private Optional<DocumentRevision> read(long subject, String owner, UUID id, Integer requested) {
        if (id == null || owner == null || owner.isBlank()) return Optional.empty();
        var latest = store.latestRevision(id);
        if (latest.isEmpty()) return Optional.empty();
        int number = requested == null ? latest.get() : requested;
        var current = store.reference(id, latest.get()).orElse(null);
        var target = number == latest.get() ? current : store.reference(id, number).orElse(null);
        if (!allowed(subject, owner, id, current) || !allowed(subject, owner, id, target)) return Optional.empty();
        var result = store.revision(id, number);
        if (result.isEmpty()) return Optional.empty();
        var value = result.get();
        // Recheck after loading, including a concurrent append with stricter current permissions.
        var now = store.latestRevision(id).flatMap(latestNumber -> store.reference(id, latestNumber)).orElse(null);
        if (!id.equals(value.documentId()) || value.number() != number || !target.equals(value.reference())
                || !allowed(subject, owner, id, now) || !allowed(subject, owner, id, target)) return Optional.empty();
        return result;
    }

    private boolean allowed(long subject, String owner, UUID id, DocumentReference reference) {
        return reference != null && owner.equals(reference.module()) && id.toString().equals(reference.id())
                && authorization.allowed(subject, reference);
    }
}
