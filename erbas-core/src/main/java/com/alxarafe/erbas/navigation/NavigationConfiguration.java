package com.alxarafe.erbas.navigation;

import java.util.List;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.*;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class NavigationConfiguration {
    @Bean NavigationRegistry navigationRegistry(List<NavigationContribution.Source> sources, ModuleRegistry modules, ResourceRegistry resources) {
        return new NavigationRegistry(sources, modules, resources);
    }
    @Bean NavigationContext navigationContext() { return user -> NavigationContext.PLATFORM; }
    @Bean EffectiveNavigation effectiveNavigation(NavigationRegistry registry, ResourceRegistry resources, ResourceRuntime runtime,
            ModuleStateService modules, MessageSource messages, NavigationContext context) {
        return new EffectiveNavigation(registry, resources, runtime, modules, messages, context);
    }
}
