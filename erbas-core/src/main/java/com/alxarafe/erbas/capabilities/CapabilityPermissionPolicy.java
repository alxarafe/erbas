package com.alxarafe.erbas.capabilities;

import com.alxarafe.erbas.security.FunctionalPermissionPolicy;

/** Functional permission-to-policy mapping, never a grant from a descriptor. */
@FunctionalInterface
public interface CapabilityPermissionPolicy extends FunctionalPermissionPolicy { }
