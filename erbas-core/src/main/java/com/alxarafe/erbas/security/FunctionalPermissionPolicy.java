package com.alxarafe.erbas.security;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Module-owned functional grants evaluated against trusted current user state. */
@FunctionalInterface
public interface FunctionalPermissionPolicy {
    boolean granted(UserIdentity user, String permission);
}
