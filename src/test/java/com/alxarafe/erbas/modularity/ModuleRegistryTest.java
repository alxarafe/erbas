package com.alxarafe.erbas.modularity;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRegistryTest {
    @Test
    void descriptorDefensivelyCopiesDependencies() {
        var dependencies = new ArrayList<>(List.of("owner"));
        var descriptor = new ModuleDescriptor("extension", ModuleApi.CURRENT_VERSION, dependencies);
        dependencies.clear();
        assertThat(descriptor.id()).isEqualTo("extension");
        assertThat(descriptor.moduleApiVersion()).isEqualTo(ModuleApi.CURRENT_VERSION);
        assertThat(descriptor.dependencies()).containsExactly("owner");
        assertThatThrownBy(() -> descriptor.dependencies().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsNoSourcesAndEmptySource() {
        assertThat(new ModuleRegistry(List.of()).modules()).isEmpty();
        assertThat(new ModuleRegistry(List.of(() -> List.of())).modules()).isEmpty();
    }

    @Test
    void acceptsOneSourceWithSupportedVersion() {
        var module = module("extension");
        assertThat(new ModuleRegistry(List.of(() -> List.of(module))).modules()).containsExactly(module);
    }

    @Test
    void rejectsUnsupportedVersionWithIdentityAndVersion() {
        int incompatibleVersion = ModuleApi.CURRENT_VERSION + 1;
        var module = new FakeModule(new ModuleDescriptor("extension", incompatibleVersion, List.of()));
        assertThatThrownBy(() -> new ModuleRegistry(List.of(() -> List.of(module))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(module.descriptor().id())
                .hasMessageContaining(Integer.toString(incompatibleVersion));
    }

    @Test
    void rejectsDuplicatesWithinOneSource() {
        var module = module("duplicate");
        assertThatThrownBy(() -> new ModuleRegistry(List.of(() -> List.of(module, module))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(module.descriptor().id());
    }

    @Test
    void rejectsDuplicatesBetweenSources() {
        var first = module("duplicate");
        var second = module(first.descriptor().id());
        assertThatThrownBy(() -> new ModuleRegistry(List.of(() -> List.of(first), () -> List.of(second))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(first.descriptor().id());
    }

    @Test
    void mergesSourcesDeterministicallyWithCrossSourceDependencies() {
        var owner = module("z-owner");
        var consumer = module("a-consumer", owner.descriptor().id());
        ModuleSource first = () -> List.of(consumer);
        ModuleSource second = () -> List.of(owner);
        var registry = new ModuleRegistry(List.of(first, second));
        assertThat(registry.modules()).containsExactly(consumer, owner);
        assertThat(new ModuleRegistry(List.of(second, first)).modules()).containsExactlyElementsOf(registry.modules());
        assertThat(ModuleGraph.order(registry.modules())).containsExactly(owner, consumer);
    }

    @Test
    void identitiesAreOpaqueAndCaseSensitive() {
        var upper = module("Extension");
        var lower = module("extension");
        assertThat(new ModuleRegistry(List.of(() -> List.of(lower, upper))).modules())
                .containsExactly(upper, lower);
    }

    @Test
    void availableCollectionIsAnImmutableSnapshot() {
        var module = module("extension");
        var input = new ArrayList<ErbasModule>(List.of(module));
        var registry = new ModuleRegistry(List.of(() -> input));
        input.clear();
        assertThat(registry.modules()).containsExactly(module);
        assertThatThrownBy(() -> registry.modules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static ErbasModule module(String id, String... dependencies) {
        return new FakeModule(new ModuleDescriptor(id, ModuleApi.CURRENT_VERSION, List.of(dependencies)));
    }

    private record FakeModule(ModuleDescriptor descriptor) implements ErbasModule {
        @Override
        public void initialize() {
        }
    }
}
