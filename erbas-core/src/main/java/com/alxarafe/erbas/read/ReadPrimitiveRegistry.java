package com.alxarafe.erbas.read;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.alxarafe.erbas.modularity.ModuleRegistry;

/** Fail-fast, deterministic in-process registration. An empty production registry is valid. */
public final class ReadPrimitiveRegistry {
    private final Map<String, Registration> registrations;
    private final List<ReadPrimitive> primitives;
    public ReadPrimitiveRegistry(Collection<ReadPrimitiveExecutor> executors, ModuleRegistry modules) {
        var owners = modules.modules().stream().map(module -> module.descriptor().id()).collect(Collectors.toSet());
        var declared = new TreeMap<String, Registration>();
        for (var executor : executors) {
            if (executor == null) throw new IllegalArgumentException("Missing primitive executor");
            var descriptor = executor.descriptor();
            if (descriptor == null || !owners.contains(descriptor.module())) {
                throw new IllegalArgumentException("Primitive owner is unavailable");
            }
            if (declared.putIfAbsent(descriptor.id(), new Registration(descriptor, executor)) != null) {
                throw new IllegalArgumentException("Duplicate primitive id");
            }
        }
        primitives = declared.values().stream().map(Registration::descriptor).toList();
        registrations = Map.copyOf(declared);
    }
    public List<ReadPrimitive> primitives() { return primitives; }
    Registration find(String id) { return registrations.get(id); }
    record Registration(ReadPrimitive descriptor, ReadPrimitiveExecutor executor) { }
}
