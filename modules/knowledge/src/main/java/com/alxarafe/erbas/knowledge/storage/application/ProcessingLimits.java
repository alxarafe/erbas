package com.alxarafe.erbas.knowledge.storage.application;

import java.time.Duration;

/** Finite per-document bounds; the existing immutable read model is materialized within these bounds. */
public record ProcessingLimits(int originalBytes, int normalizedBytes, int pages, Duration timeout) {
    public static final ProcessingLimits DEFAULT = new ProcessingLimits(10 * 1024 * 1024, 4 * 1024 * 1024,
            500, Duration.ofSeconds(10));
    public ProcessingLimits {
        if (originalBytes <= 0 || originalBytes == Integer.MAX_VALUE || normalizedBytes <= 0
                || pages <= 0 || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive finite processing limits are required");
        timeout.toNanos();
    }
    public void validate(com.alxarafe.erbas.knowledge.storage.domain.DocumentContent content) {
        if (content.original().length > originalBytes || content.text().length() > normalizedBytes
                || content.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > normalizedBytes
                || content.processing().pageEnds().size() > pages)
            throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
    }
}
