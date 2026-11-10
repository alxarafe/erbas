package com.alxarafe.erbas.knowledge.storage.application;

/** Technical failures contain no content, physical path or credentials. */
public final class StorageFailure extends RuntimeException {
    public enum Kind { READ, WRITE, UNAVAILABLE, NOT_FOUND, INTEGRITY }
    private final Kind kind;
    public StorageFailure(Kind kind) {
        super("Document storage failure: " + kind);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
