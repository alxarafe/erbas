package com.alxarafe.erbas.capabilities;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Fail-fast registration; every registered READ must have exactly one owned handler. */
public final class CapabilityInvocationRegistry {
    private final Map<String, CapabilityInvocationHandler> handlers;
    public CapabilityInvocationRegistry(CapabilityRegistry registry, Collection<CapabilityInvocationHandler> declarations) {
        var registered = new TreeMap<String, CapabilityInvocationHandler>();
        for (var handler : declarations) {
            if (handler == null) throw new IllegalArgumentException("Missing invocation handler");
            var capability = registry.capabilities().stream().filter(value -> value.id().equals(handler.capabilityId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown handler capability"));
            if (!Capability.READ.equals(capability.effect()) || !capability.module().equals(handler.module())) {
                throw new IllegalArgumentException("Invalid invocation handler effect or ownership");
            }
            if (registered.putIfAbsent(capability.id(), handler) != null) {
                throw new IllegalArgumentException("Duplicate invocation handler");
            }
        }
        for (var capability : registry.capabilities()) {
            if (Capability.READ.equals(capability.effect()) && !registered.containsKey(capability.id())) {
                throw new IllegalArgumentException("Missing READ invocation handler");
            }
        }
        handlers = Map.copyOf(registered);
    }
    public CapabilityInvocationHandler handler(String id) { return handlers.get(id); }
}
