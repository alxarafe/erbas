package com.alxarafe.erbas.capabilities;

import java.util.List;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleUnavailableException;

public final class CapabilityDiscovery {
    private final CapabilityRegistry registry;
    private final ModuleStateService states;
    private final List<CapabilityPermissionPolicy> policies;

    public CapabilityDiscovery(CapabilityRegistry registry, ModuleStateService states, List<CapabilityPermissionPolicy> policies) {
        this.registry = registry;
        this.states = states;
        this.policies = List.copyOf(policies);
    }
    public List<Capability> effective(UserIdentity user) {
        if (user == null || !user.enabled()) return List.of();
        return registry.capabilities().stream().filter(capability -> enabled(capability.module()))
                .filter(capability -> capability.requiredPermissions().stream().allMatch(permission ->
                        policies.stream().anyMatch(policy -> policy.granted(user, permission))))
                .toList();
    }
    private boolean enabled(String module) {
        try { return states.isEnabled(module); }
        catch (ModuleUnavailableException unavailable) { return false; }
    }
}
