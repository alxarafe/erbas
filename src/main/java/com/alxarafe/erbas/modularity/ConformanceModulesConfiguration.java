package com.alxarafe.erbas.modularity;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Reserved development descriptors; no functional API or business behavior. */
@Configuration(proxyBeanMethods = false)
@Profile(ConformanceModulesConfiguration.VALIDATION_PROFILE)
@ConditionalOnProperty(name = ConformanceModulesConfiguration.ENABLED_PROPERTY, havingValue = "true")
public class ConformanceModulesConfiguration {
    public static final String VALIDATION_PROFILE = "validation";
    public static final String ENABLED_PROPERTY = "erbas.modules.conformance.enabled";
    public static final String MATH_ID = "erbas.dev.math";
    public static final String STATISTICS_ID = "erbas.dev.statistics";

    @Bean
    public ModuleSource conformanceModuleSource() {
        var math = new DescriptorModule(new ModuleDescriptor(MATH_ID, ModuleApi.CURRENT_VERSION, List.of()));
        var statistics = new DescriptorModule(new ModuleDescriptor(STATISTICS_ID, ModuleApi.CURRENT_VERSION, List.of(MATH_ID)));
        return () -> List.of(math, statistics);
    }

    private record DescriptorModule(ModuleDescriptor descriptor) implements ErbasModule {
        @Override public void initialize() { }
    }
}
