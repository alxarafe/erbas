package com.alxarafe.erbas.modularity;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRuntimeTest {
    @Test
    void initializesExactlyOnceInResolvedOrder() {
        List<String> calls = new ArrayList<>();
        var a = new FakeModule("a", List.of(), () -> calls.add("a"));
        var b = new FakeModule("b", List.of(a.id()), () -> calls.add("b"));
        var c = new FakeModule("c", List.of(b.id()), () -> calls.add("c"));
        var ordered = ModuleGraph.order(List.of(c, a, b));
        var runtime = new ModuleRuntime(ordered);
        assertThat(runtime.modules()).containsExactlyElementsOf(ordered);
        runtime.initialize();
        assertThat(calls).containsExactly(a.id(), b.id(), c.id());
        assertThatThrownBy(runtime::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(calls).containsExactly(a.id(), b.id(), c.id());
    }

    @Test
    void propagatesFailureAndStopsWithoutRetrying() {
        List<String> calls = new ArrayList<>();
        var failure = new IllegalStateException("fixture failure");
        var a = new FakeModule("a", List.of(), () -> calls.add("a"));
        var b = new FakeModule("b", List.of(a.id()), () -> {
            calls.add("b");
            throw failure;
        });
        var c = new FakeModule("c", List.of(b.id()), () -> calls.add("c"));
        var runtime = new ModuleRuntime(ModuleGraph.order(List.of(c, b, a)));
        assertThatThrownBy(runtime::initialize).isSameAs(failure);
        assertThat(calls).containsExactly(a.id(), b.id());
        assertThatThrownBy(runtime::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(calls).containsExactly(a.id(), b.id());
    }

    @Test
    void copiesInputAndExposesImmutableModules() {
        var module = new FakeModule("a", List.of(), () -> { });
        var input = new ArrayList<ErbasModule>(List.of(module));
        var runtime = new ModuleRuntime(input);
        input.clear();
        assertThat(runtime.modules()).containsExactly(module);
        assertThatThrownBy(() -> runtime.modules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsEmptyRuntime() {
        var runtime = new ModuleRuntime(List.of());
        runtime.initialize();
        assertThat(runtime.modules()).isEmpty();
    }

    private record FakeModule(String id, List<String> dependencies, Runnable action) implements ErbasModule {
        @Override
        public void initialize() {
            action.run();
        }
    }
}
