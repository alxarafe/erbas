package com.alxarafe.erbas.read;

import java.time.Duration;
import java.time.Instant;

/** A primitive execution identity is separate from CORE-CAP-002 and carries no payload. */
@FunctionalInterface
public interface ReadPrimitiveAuditSink {
    void record(Entry entry);
    enum Outcome { completed, denied, failed }
    record Entry(String invocationId, String primitiveId, String module, String subject,
            ReadPrimitiveRouter.Origin origin, Instant timestamp, Duration duration, Outcome outcome,
            ReadPrimitiveRouter.Failure failure) { }
}
