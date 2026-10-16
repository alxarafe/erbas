package com.alxarafe.erbas.modularity.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.alxarafe.erbas.modularity.ErbasModule;
import com.alxarafe.erbas.modularity.ModuleApi;
import com.alxarafe.erbas.modularity.ModuleDescriptor;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleStateStore;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleEndpointInterceptorTest {
    private static final String ID = "request-boundary-fixture";

    @Test
    void asynchronousRedispatchUsesOneDecisionButNextRequestReadsCurrentState() throws Exception {
        var store = new CountingStore();
        ErbasModule module = new ErbasModule() {
            @Override public ModuleDescriptor descriptor() { return new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of()); }
            @Override public void initialize() { }
        };
        var states = new ModuleStateService(new ModuleRegistry(List.of(() -> List.of(module))), store);
        var gate = new ModuleEndpointInterceptor(states);
        var handler = new HandlerMethod(new ModuleController(), ModuleController.class.getMethod("value"));
        var request = new MockHttpServletRequest();
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
        states.disable(ID);
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
        assertThat(store.reads).isEqualTo(1);
        var disabled = new MockHttpServletResponse();
        assertThat(gate.preHandle(new MockHttpServletRequest(), disabled, handler)).isFalse();
        assertThat(store.reads).isEqualTo(2);
        assertThat(disabled.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void coreControllersAndNonControllerHandlersNeverConsultModuleState() throws Exception {
        var store = new CountingStore();
        var gate = new ModuleEndpointInterceptor(new ModuleStateService(new ModuleRegistry(List.of()), store));
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        assertThat(gate.preHandle(request, response, new Object())).isTrue();
        assertThat(gate.preHandle(request, response,
                new HandlerMethod(new CoreController(), CoreController.class.getMethod("value")))).isTrue();
        assertThat(store.reads).isZero();
    }

    @ModuleEndpoint(ID)
    static class ModuleController {
        public String value() { return "fixture"; }
    }

    static class CoreController {
        public String value() { return "core-fixture"; }
    }

    private static class CountingStore implements ModuleStateStore {
        private boolean enabled = ModuleStateService.INITIAL_ENABLED;
        private int reads;
        @Override public Optional<Boolean> find(String id) { reads++; return Optional.of(enabled); }
        @Override public void update(Consumer<Map<String, Boolean>> change) {
            var snapshot = new java.util.HashMap<>(Map.of(ID, enabled));
            change.accept(snapshot);
            enabled = snapshot.get(ID);
        }
    }
}
