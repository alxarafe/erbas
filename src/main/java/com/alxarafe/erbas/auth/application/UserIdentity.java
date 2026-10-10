package com.alxarafe.erbas.auth.application;

import java.security.Principal;

/** Current user state, without credentials. IDs remain opaque outside the backend. */
public record UserIdentity(long userId, String email, boolean enabled, boolean admin) implements Principal {
    @Override
    public String getName() {
        return Long.toString(userId);
    }

    @Override
    public String toString() {
        return "UserIdentity[userId=" + userId + ", enabled=" + enabled + ", admin=" + admin + "]";
    }
}
