package com.alxarafe.erbas.modularity;

/** No module in the current registry has this case-sensitive identity. */
public final class ModuleUnavailableException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ModuleUnavailableException(String moduleId) {
        super("Module '" + moduleId + "' is not AVAILABLE");
    }
}
