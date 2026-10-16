package com.alxarafe.erbas.modularity;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleGraphTest {
    @Test
    void acceptsNoModules() {
        assertThat(ModuleGraph.order(List.of())).isEmpty();
    }

    @Test
    void retainsSingleModule() {
        var module = new FakeModule("a", List.of());
        assertThat(ModuleGraph.order(List.of(module))).containsExactly(module);
    }

    @Test
    void ordersIndependentModulesNaturallyRegardlessOfRegistrationOrder() {
        var upper = new FakeModule("B", List.of());
        var lower = new FakeModule("a", List.of());
        var last = new FakeModule("z", List.of());
        assertThat(ModuleGraph.order(List.of(last, lower, upper))).containsExactly(upper, lower, last);
        assertThat(ModuleGraph.order(List.of(lower, upper, last))).containsExactly(upper, lower, last);
    }

    @Test
    void ordersDependencyChainBeforeConsumers() {
        var a = new FakeModule("a", List.of());
        var b = new FakeModule("b", List.of(a.descriptor().id()));
        var c = new FakeModule("c", List.of(b.descriptor().id()));
        assertThat(ModuleGraph.order(List.of(c, b, a))).containsExactly(a, b, c);
    }

    @Test
    void ordersMultipleDependenciesNaturallyAndVisitsSharedDependencyOnce() {
        var root = new FakeModule("root", List.of());
        var b = new FakeModule("b", List.of(root.descriptor().id()));
        var c = new FakeModule("c", List.of(root.descriptor().id()));
        var a = new FakeModule("a", List.of(c.descriptor().id(), b.descriptor().id()));
        assertThat(ModuleGraph.order(List.of(a, c, root, b))).containsExactly(root, b, c, a);
        var reversed = new FakeModule(a.descriptor().id(), List.of(b.descriptor().id(), c.descriptor().id()));
        assertThat(ModuleGraph.order(List.of(b, root, c, reversed)))
                .containsExactly(root, b, c, reversed);
    }

    @Test
    void rejectsMissingDependencyWithBothIds() {
        var module = new FakeModule("consumer", List.of("missing"));
        assertThatThrownBy(() -> ModuleGraph.order(List.of(module)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(module.descriptor().id())
                .hasMessageContaining(module.descriptor().dependencies().getFirst());
    }

    @Test
    void rejectsDuplicateId() {
        var first = new FakeModule("duplicate", List.of());
        var second = new FakeModule(first.descriptor().id(), List.of());
        assertThatThrownBy(() -> ModuleGraph.order(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(first.descriptor().id());
    }

    @Test
    void rejectsSelfDependency() {
        var module = new FakeModule("self", List.of("self"));
        assertThatThrownBy(() -> ModuleGraph.order(List.of(module)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle")
                .hasMessageContaining(module.descriptor().id());
    }

    @Test
    void rejectsIndirectCycle() {
        var a = new FakeModule("a", List.of("b"));
        var b = new FakeModule("b", List.of("c"));
        var c = new FakeModule("c", List.of(a.descriptor().id()));
        assertThatThrownBy(() -> ModuleGraph.order(List.of(c, b, a)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle")
                .hasMessageContaining(a.descriptor().id());
    }

    @Test
    void exposesImmutableResult() {
        var module = new FakeModule("a", List.of());
        var result = ModuleGraph.order(List.of(module));
        assertThatThrownBy(() -> result.add(module)).isInstanceOf(UnsupportedOperationException.class);
    }

    private record FakeModule(ModuleDescriptor descriptor) implements ErbasModule {
        FakeModule(String id, List<String> dependencies) {
            this(new ModuleDescriptor(id, ModuleApi.CURRENT_VERSION, dependencies));
        }

        @Override
        public void initialize() {
        }
    }
}
