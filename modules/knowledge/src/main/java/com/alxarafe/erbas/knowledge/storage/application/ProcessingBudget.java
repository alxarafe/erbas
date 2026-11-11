package com.alxarafe.erbas.knowledge.storage.application;

import java.util.function.LongSupplier;

/** Cooperative deadline checked by processors; not a hard CPU or heap sandbox. */
public final class ProcessingBudget {
    private final ProcessingLimits limits;
    private final LongSupplier clock;
    private final long started;
    public ProcessingBudget(ProcessingLimits limits) { this(limits, System::nanoTime); }
    public ProcessingBudget(ProcessingLimits limits, LongSupplier clock) {
        this.limits = limits; this.clock = clock; this.started = clock.getAsLong();
    }
    public ProcessingLimits limits() { return limits; }
    public void check() {
        if (Thread.currentThread().isInterrupted() || clock.getAsLong() - started >= limits.timeout().toNanos())
            throw new ProcessingFailure(ProcessingFailure.Kind.TIMEOUT);
    }
}
