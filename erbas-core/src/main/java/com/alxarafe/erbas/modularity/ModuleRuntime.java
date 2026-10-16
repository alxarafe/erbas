package com.alxarafe.erbas.modularity;

import java.util.List;

/** Owns an already ordered module list and performs one sequential initialization. */
public final class ModuleRuntime {
    private final List<ErbasModule> modules;
    private boolean initializationStarted;

    public ModuleRuntime(List<ErbasModule> orderedModules) {
        modules = List.copyOf(orderedModules);
    }

    public List<ErbasModule> modules() {
        return modules;
    }

    public void initialize() {
        if (initializationStarted) {
            throw new IllegalStateException("Module runtime initialization has already started");
        }
        initializationStarted = true;
        for (ErbasModule module : modules) {
            module.initialize();
        }
    }
}
