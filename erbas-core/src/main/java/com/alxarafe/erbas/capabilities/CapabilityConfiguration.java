package com.alxarafe.erbas.capabilities;

import java.util.List;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CapabilityConfiguration {
    @Bean
    public CapabilityRegistry capabilityRegistry(List<CapabilityProvider> providers, ModuleRegistry modules,
            CapabilitySchemaCatalog schemas) {
        return new CapabilityRegistry(providers, modules, schemas);
    }
    @Bean
    public CapabilityDiscovery capabilityDiscovery(CapabilityRegistry registry, ModuleStateService states,
            List<CapabilityPermissionPolicy> policies) {
        return new CapabilityDiscovery(registry, states, policies);
    }
}
