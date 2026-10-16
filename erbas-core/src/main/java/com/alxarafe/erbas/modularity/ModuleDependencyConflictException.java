package com.alxarafe.erbas.modularity;

/** A proposed state violates an available module's required dependency. */
public final class ModuleDependencyConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ModuleDependencyConflictException(String moduleId, String dependencyId) {
        super("Enabled module '" + moduleId + "' requires enabled dependency '" + dependencyId + "'");
    }
}
