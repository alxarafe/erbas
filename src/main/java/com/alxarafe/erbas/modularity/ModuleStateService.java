package com.alxarafe.erbas.modularity;

import java.util.Map;
import java.util.TreeMap;

/** Administrative state only: availability and technical initialization remain separate. */
public final class ModuleStateService {
    public static final boolean INITIAL_ENABLED = true;
    private final Map<String, ModuleDescriptor> available = new TreeMap<>();
    private final ModuleStateStore store;

    public ModuleStateService(ModuleRegistry registry, ModuleStateStore store) {
        for (ErbasModule module : registry.modules()) {
            available.put(module.descriptor().id(), module.descriptor());
        }
        this.store = store;
    }

    public void synchronize() {
        if (available.isEmpty()) return;
        store.update(states -> {
            for (String id : available.keySet()) states.putIfAbsent(id, INITIAL_ENABLED);
            validate(states);
        });
    }

    public boolean isEnabled(String moduleId) {
        requireAvailable(moduleId);
        return store.find(moduleId).orElseThrow(() ->
                new IllegalStateException("Module state is missing for '" + moduleId + "'"));
    }

    public void requireEnabled(String moduleId) {
        if (!isEnabled(moduleId)) throw new IllegalStateException("Module '" + moduleId + "' is disabled");
    }

    public void enable(String moduleId) {
        change(moduleId, true);
    }

    public void disable(String moduleId) {
        change(moduleId, false);
    }

    private void change(String moduleId, boolean enabled) {
        requireAvailable(moduleId);
        store.update(states -> {
            if (!states.containsKey(moduleId)) {
                throw new IllegalStateException("Module state is missing for '" + moduleId + "'");
            }
            states.put(moduleId, enabled);
            validate(states);
        });
    }

    private void requireAvailable(String moduleId) {
        if (!available.containsKey(moduleId)) {
            throw new IllegalArgumentException("Module '" + moduleId + "' is not AVAILABLE");
        }
    }

    private void validate(Map<String, Boolean> states) {
        for (var module : available.values()) {
            var enabled = states.get(module.id());
            if (enabled == null) throw new IllegalStateException("Module state is missing for '" + module.id() + "'");
            if (!enabled) continue;
            for (String dependency : module.dependencies()) {
                if (!Boolean.TRUE.equals(states.get(dependency))) {
                    throw new IllegalStateException("Enabled module '" + module.id()
                            + "' requires enabled dependency '" + dependency + "'");
                }
            }
        }
    }
}
