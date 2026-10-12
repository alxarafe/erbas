package com.alxarafe.erbas.modularity;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModularityConfigurationTest {
    @Test
    void springConstructsModulesAndInitializesBeforeRefreshReturns() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(ModularityConfiguration.class);
            context.registerBean(InitializationLog.class);
            context.registerBean(ConsumerModule.class);
            context.registerBean(FoundationModule.class);
            context.refresh();
            var runtime = context.getBean(ModuleRuntime.class);
            assertThat(runtime.modules()).containsExactly(context.getBean(FoundationModule.class),
                    context.getBean(ConsumerModule.class));
            assertThat(context.getBean(InitializationLog.class).calls)
                    .containsExactly(FoundationModule.ID, ConsumerModule.ID);
        }
    }

    @Test
    void springCreatesEmptyRuntimeWithoutModuleBeans() {
        try (var context = new AnnotationConfigApplicationContext(ModularityConfiguration.class)) {
            assertThat(context.getBean(ModuleRuntime.class).modules()).isEmpty();
        }
    }

    @Test
    void initializationFailurePreventsSuccessfulContextRefresh() {
        var failure = new IllegalStateException("fixture startup failure");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(ModularityConfiguration.class);
            context.registerBean(ErbasModule.class, () -> new ErbasModule() {
                @Override
                public String id() {
                    return "failing";
                }

                @Override
                public List<String> dependencies() {
                    return List.of();
                }

                @Override
                public void initialize() {
                    throw failure;
                }
            });
            assertThatThrownBy(context::refresh).isSameAs(failure);
            assertThat(context.isActive()).isFalse();
        }
    }

    static class InitializationLog {
        final List<String> calls = new ArrayList<>();
    }

    static class FoundationModule implements ErbasModule {
        static final String ID = "foundation";
        private final InitializationLog log;

        FoundationModule(InitializationLog log) {
            this.log = log;
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public List<String> dependencies() {
            return List.of();
        }

        @Override
        public void initialize() {
            log.calls.add(ID);
        }
    }

    static class ConsumerModule implements ErbasModule {
        static final String ID = "consumer";
        private final InitializationLog log;

        ConsumerModule(InitializationLog log) {
            this.log = log;
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public List<String> dependencies() {
            return List.of(FoundationModule.ID);
        }

        @Override
        public void initialize() {
            log.calls.add(ID);
        }
    }
}
