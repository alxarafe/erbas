package com.alxarafe.erbas.knowledge;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Independent document visibility authority, evaluated on every query. */
@FunctionalInterface
public interface DocumentVisibilityPolicy {
    boolean visible(UserIdentity user, DocumentReference document);
}
