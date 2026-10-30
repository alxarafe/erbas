package com.alxarafe.erbas.resources;

/** Explicit safe outcomes; adapter exceptions are never interpreted as business errors. */
public final class ResourceFailure extends RuntimeException {
    public enum Code {
        invalid_request, unauthorized, forbidden, resource_not_found, resource_action_not_found,
        resource_operation_not_supported, resource_conflict, resource_action_conflict,
        resource_action_unavailable, resource_execution_failed, module_disabled, resource_execution_unavailable
    }
    private final Code code;
    public ResourceFailure(Code code) { this.code = code; }
    public Code code() { return code; }
    public static ResourceFailure invalid() { return new ResourceFailure(Code.invalid_request); }
}
