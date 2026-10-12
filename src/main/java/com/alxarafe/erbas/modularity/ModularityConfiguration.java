package com.alxarafe.erbas.modularity;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModularityConfiguration {
    @Bean
    public SpringModuleSource springModuleSource(List<ErbasModule> modules) {
        return new SpringModuleSource(modules);
    }

    @Bean
    public ModuleRegistry moduleRegistry(List<ModuleSource> sources) {
        return new ModuleRegistry(sources);
    }

    @Bean
    public ModuleRuntime moduleRuntime(ModuleRegistry registry) {
        return new ModuleRuntime(ModuleGraph.order(registry.modules()));
    }

    @Bean
    public SmartInitializingSingleton moduleInitialization(ModuleRuntime runtime) {
        return runtime::initialize;
    }
}
