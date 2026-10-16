package com.alxarafe.erbas.modularity;

import java.util.List;

/** The current built-in source: modules already constructed by Spring. */
public record SpringModuleSource(List<ErbasModule> modules) implements ModuleSource {
    public SpringModuleSource {
        modules = List.copyOf(modules);
    }
}
