package com.alxarafe.erbas.modularity;

import java.util.List;
import java.util.Objects;

/** Stable, opaque module identity and immutable dependency metadata. */
public record ModuleDescriptor(String id, int moduleApiVersion, List<String> dependencies) {
    public ModuleDescriptor {
        Objects.requireNonNull(id, "Module id");
        dependencies = List.copyOf(dependencies);
    }
}
