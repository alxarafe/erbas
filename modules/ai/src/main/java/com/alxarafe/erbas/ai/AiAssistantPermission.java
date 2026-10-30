package com.alxarafe.erbas.ai;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Independent of CORE business capability grants. */
@FunctionalInterface
public interface AiAssistantPermission {
    String INVOKE = "ai.assistant.invoke";
    boolean granted(UserIdentity user);
}
