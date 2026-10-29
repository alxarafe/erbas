package com.alxarafe.erbas.capabilities;

import java.util.Collection;

/** Module-owned declarations contributed through explicit composition. */
@FunctionalInterface
public interface CapabilityProvider {
    Collection<Capability> capabilities();
}
