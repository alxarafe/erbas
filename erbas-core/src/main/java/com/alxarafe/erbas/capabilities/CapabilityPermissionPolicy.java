package com.alxarafe.erbas.capabilities;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Functional permission-to-policy mapping, never a grant from a descriptor. */
@FunctionalInterface
public interface CapabilityPermissionPolicy {
    boolean granted(UserIdentity user, String permission);
}
