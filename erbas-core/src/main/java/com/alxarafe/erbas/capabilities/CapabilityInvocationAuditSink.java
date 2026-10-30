package com.alxarafe.erbas.capabilities;

import java.time.Instant;
import java.time.Duration;

/** Deliberately cannot carry input, output, credentials or exception details. */
@FunctionalInterface
public interface CapabilityInvocationAuditSink {
    void record(Entry entry);
    enum Outcome { completed, denied, failed }
    record Entry(String invocationId, String correlationId, String capabilityId, String module,
            String subject, Instant timestamp, Outcome outcome, Duration duration, String errorCode) { }
}
