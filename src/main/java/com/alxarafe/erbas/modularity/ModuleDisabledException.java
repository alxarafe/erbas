package com.alxarafe.erbas.modularity;

/** A valid available module has been administratively disabled. */
public final class ModuleDisabledException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String moduleId;

    public ModuleDisabledException(String moduleId) {
        super("Module '" + moduleId + "' is disabled");
        this.moduleId = moduleId;
    }

    public String moduleId() { return moduleId; }
}
