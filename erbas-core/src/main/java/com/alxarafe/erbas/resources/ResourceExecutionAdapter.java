package com.alxarafe.erbas.resources;

import java.util.*;
import java.math.BigInteger;
import com.alxarafe.erbas.auth.application.UserIdentity;

/** Module persistence boundary. All values are typed and validated by Core. */
public interface ResourceExecutionAdapter {
    String resourceId();
    default boolean contextAllowed(UserIdentity user) { return true; }
    ResourcePage list(UserIdentity user, ResourceQuery query);
    Optional<Map<String, Object>> get(UserIdentity user, Object id);
    Map<String, Object> create(UserIdentity user, Map<String, Object> input);
    Map<String, Object> update(UserIdentity user, Object id, Map<String, Object> merged);
    boolean delete(UserIdentity user, Object id);

    /** total=null means unavailable internally; the HTTP projection omits it. */
    record ResourcePage(List<Map<String, Object>> items, BigInteger offset, int limit, BigInteger total) { }
}
