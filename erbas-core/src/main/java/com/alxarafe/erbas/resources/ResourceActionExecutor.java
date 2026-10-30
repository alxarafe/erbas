package com.alxarafe.erbas.resources;

import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;

public interface ResourceActionExecutor {
    int MAX_REASON_LENGTH = 100;
    String resourceId();
    String actionName();
    default Availability availability(UserIdentity user, List<Object> targets) { return new Availability(true, null); }
    void execute(UserIdentity user, List<Object> targets, Map<String, Object> parameters);
    record Availability(boolean available, String unavailableReason) {
        public Availability {
            if (unavailableReason != null && (available || unavailableReason.length() > MAX_REASON_LENGTH
                    || !unavailableReason.matches("[a-z][a-z0-9_]*"))) throw new IllegalArgumentException("Invalid availability code");
        }
    }
}
