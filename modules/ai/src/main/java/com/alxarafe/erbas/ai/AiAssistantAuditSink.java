package com.alxarafe.erbas.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Payload-free AI audit, linking only successful CORE invocation identities. */
@FunctionalInterface
public interface AiAssistantAuditSink {
    void record(Entry entry);
    enum Outcome { completed, denied, failed }
    sealed interface ToolUsage permits CapabilityUsage, PrimitiveUsage { String invocationId(); }
    record CapabilityUsage(String capabilityId, String invocationId) implements ToolUsage { }
    record PrimitiveUsage(String primitiveId, String invocationId) implements ToolUsage { }
    record Entry(String invocationId, String subject, Instant timestamp, Outcome outcome, Duration duration,
            String correlationId, List<ToolUsage> toolsUsed, String errorCode) {
        public Entry { toolsUsed = List.copyOf(toolsUsed); }
    }
}
