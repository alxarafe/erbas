package com.alxarafe.erbas.resources;

import java.util.List;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ResourceConfiguration {
    @Bean ResourceRegistry resourceRegistry(List<ResourceRegistry.Source> sources, List<ResourceExecutionAdapter> adapters,
            List<ResourceActionExecutor> actions, ModuleRegistry modules) { return new ResourceRegistry(sources, adapters, actions, modules); }
    @Bean ResourceRuntime resourceRuntime(ResourceRegistry registry, ModuleStateService modules, UserAdministration users,
            List<FunctionalPermissionPolicy> policies) {
        return new ResourceRuntime(registry, modules, id -> users.find(Long.toString(id)), policies);
    }
    @Bean ResourceMetadata resourceMetadata(ResourceRegistry registry, ResourceRuntime runtime) { return new ResourceMetadata(registry, runtime); }
    @Bean ResourceActions resourceActions(ResourceRegistry registry, ResourceRuntime runtime) { return new ResourceActions(registry, runtime); }
}
