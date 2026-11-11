package com.alxarafe.erbas.knowledge.storage.application;

/** Technical internal failure without document contents or parser diagnostics. */
public final class ProcessingFailure extends RuntimeException {
    public enum Kind { UNSUPPORTED, INVALID, LIMIT, TIMEOUT, NO_TEXT }
    private final Kind kind;
    public ProcessingFailure(Kind kind) { super("Document processing failed: " + kind); this.kind = kind; }
    public Kind kind() { return kind; }
}
