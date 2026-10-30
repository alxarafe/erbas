package com.alxarafe.erbas.navigation;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Trusted context identity for cache isolation. Current composition has only platform context. */
@FunctionalInterface
public interface NavigationContext {
    String PLATFORM = "platform";
    String key(UserIdentity currentUser);
}
