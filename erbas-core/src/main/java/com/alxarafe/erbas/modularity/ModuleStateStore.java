package com.alxarafe.erbas.modularity;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Persistence authority; updates serialize before reading and commit atomically. */
public interface ModuleStateStore {
    Optional<Boolean> find(String moduleId);

    void update(Consumer<Map<String, Boolean>> change);
}
