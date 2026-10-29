package com.alxarafe.erbas.capabilities;

import java.util.Set;

/** Names from selected published bundled OpenAPI; no schema bodies or resolver. */
public record CapabilitySchemaCatalog(Set<String> components) {
    public CapabilitySchemaCatalog { components = Set.copyOf(components); }
    public boolean contains(Capability.SchemaReference reference) { return components.contains(reference.component()); }
}
