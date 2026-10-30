package com.alxarafe.erbas.capabilities;

import tools.jackson.databind.JsonNode;

/** Explicit provider-owned execution and schema validation, separate from public metadata. */
public interface CapabilityInvocationHandler {
    String capabilityId();
    String module();
    Object validateInput(JsonNode input);
    Object execute(CapabilityInvocationContext context, Object input);
    boolean validOutput(Object output, Object input);

    final class InvalidInput extends RuntimeException { }
}
