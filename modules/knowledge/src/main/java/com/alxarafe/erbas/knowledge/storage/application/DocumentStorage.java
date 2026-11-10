package com.alxarafe.erbas.knowledge.storage.application;

import java.io.InputStream;
import java.io.OutputStream;

/** Trusted infrastructure SPI. Never expose this as a resource, tool or authorized read API. */
public interface DocumentStorage {
    /** Caller owns source. Return only after publishing a complete, durable, immutable object. */
    StoredObject write(StorageContext context, InputStream source);

    /**
     * Caller owns target and must stage/discard it until this method succeeds.
     * Verify expected size and SHA-256; partial bytes must never reach a disclosure boundary.
     */
    void read(StorageContext context, StoredObject object, OutputStream target);
}
