package com.alxarafe.erbas.modularity;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class ConformanceModulesConfigurationTest {
    @Test
    void normalAndProductionCompositionHaveZeroModulesEvenWithTheOptInProperty() {
        for (String profile : List.of("default", "production")) {
            try (var context = context(profile, true)) {
                assertThat(context.getBean(ModuleRegistry.class).modules()).isEmpty();
                assertThat(context.getBean(ModuleRuntime.class).modules()).isEmpty();
            }
        }
    }

    @Test
    void validationProfileAloneDoesNotRegisterConformanceModules() {
        try (var context = context(ConformanceModulesConfiguration.VALIDATION_PROFILE, false)) {
            assertThat(context.getBean(ModuleRegistry.class).modules()).isEmpty();
        }
    }

    @Test
    void explicitValidationSourceUsesTheExistingRegistryGraphAndRuntime() {
        try (var context = context(ConformanceModulesConfiguration.VALIDATION_PROFILE, true)) {
            var registry = context.getBean(ModuleRegistry.class);
            assertThat(registry.modules().stream().map(ErbasModule::descriptor)).containsExactly(
                    new ModuleDescriptor(ConformanceModulesConfiguration.MATH_ID, ModuleApi.CURRENT_VERSION, List.of()),
                    new ModuleDescriptor(ConformanceModulesConfiguration.STATISTICS_ID, ModuleApi.CURRENT_VERSION,
                            List.of(ConformanceModulesConfiguration.MATH_ID)));
            assertThat(context.getBean(ModuleRuntime.class).modules()).containsExactlyElementsOf(registry.modules());
            assertThat(context.getBeansOfType(ErbasModule.class)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext context(String profile, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("conformance-fixture",
                Map.of(ConformanceModulesConfiguration.ENABLED_PROPERTY, Boolean.toString(enabled))));
        context.register(ModularityConfiguration.class, ConformanceModulesConfiguration.class);
        context.refresh();
        return context;
    }
}
