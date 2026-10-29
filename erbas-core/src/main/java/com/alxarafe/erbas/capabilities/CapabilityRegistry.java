package com.alxarafe.erbas.capabilities;

import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.alxarafe.erbas.modularity.ModuleRegistry;

/** Startup validation is fail-fast, consistent with ModuleRegistry. */
public final class CapabilityRegistry {
    private final List<Capability> capabilities;

    public CapabilityRegistry(Collection<CapabilityProvider> providers, ModuleRegistry modules,
            CapabilitySchemaCatalog schemas) {
        var owners = modules.modules().stream().map(module -> module.descriptor().id()).collect(Collectors.toSet());
        var declarations = new TreeMap<String, Capability>();
        for (var provider : providers) {
            for (var capability : provider.capabilities()) {
                if (capability == null || !owners.contains(capability.module())
                        || !schemas.contains(capability.inputSchema()) || !schemas.contains(capability.outputSchema())) {
                    throw new IllegalArgumentException("Capability owner or schema target is unavailable");
                }
                if (declarations.putIfAbsent(capability.id(), capability) != null) {
                    throw new IllegalArgumentException("Duplicate capability id '" + capability.id() + "'");
                }
            }
        }
        capabilities = List.copyOf(declarations.values());
    }
    public List<Capability> capabilities() { return capabilities; }
}
