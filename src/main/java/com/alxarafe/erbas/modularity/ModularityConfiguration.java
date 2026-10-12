package com.alxarafe.erbas.modularity;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModularityConfiguration {
    @Bean
    public ModuleRuntime moduleRuntime(List<ErbasModule> modules) {
        return new ModuleRuntime(ModuleGraph.order(modules));
    }

    @Bean
    public SmartInitializingSingleton moduleInitialization(ModuleRuntime runtime) {
        return runtime::initialize;
    }
}
