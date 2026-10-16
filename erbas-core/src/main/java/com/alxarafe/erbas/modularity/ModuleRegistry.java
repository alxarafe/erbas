package com.alxarafe.erbas.modularity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Presence here is the authority for AVAILABLE modules in this startup. */
public final class ModuleRegistry {
    private final List<ErbasModule> modules;

    public ModuleRegistry(Collection<? extends ModuleSource> sources) {
        Map<String, ErbasModule> available = new TreeMap<>();
        for (ModuleSource source : sources) {
            for (ErbasModule module : source.modules()) {
                var descriptor = module.descriptor();
                if (descriptor.moduleApiVersion() != ModuleApi.CURRENT_VERSION) {
                    throw new IllegalArgumentException("Module '" + descriptor.id()
                            + "' declares unsupported Module API version " + descriptor.moduleApiVersion()
                            + "; supported version is " + ModuleApi.CURRENT_VERSION);
                }
                if (available.putIfAbsent(descriptor.id(), module) != null) {
                    throw new IllegalArgumentException("Duplicate module id '" + descriptor.id() + "'");
                }
            }
        }
        modules = List.copyOf(available.values());
    }

    public List<ErbasModule> modules() {
        return modules;
    }
}
