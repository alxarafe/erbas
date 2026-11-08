package com.alxarafe.erbas.knowledge;

import com.alxarafe.erbas.auth.application.UserIdentity;

/** Trusted module-owned restriction. It can veto access, never grant functional permission. */
public interface DocumentAccessPolicy {
    String module();
    boolean allowed(UserIdentity user, DocumentReference document);
}
