package com.alxarafe.erbas.capabilities;

import java.util.List;
import com.alxarafe.erbas.auth.application.UserIdentity;

/** Server state only; absent correlation is represented by null. */
public record CapabilityInvocationContext(String invocationId, String correlationId, UserIdentity subject,
        String capabilityId, String module, List<String> effectivePermissions) {
    public static final String USER_CALLER = "user";
    public CapabilityInvocationContext { effectivePermissions = List.copyOf(effectivePermissions); }
    public String caller() { return USER_CALLER; }
}
