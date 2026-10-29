package com.alxarafe.erbas.capabilities;

import java.util.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.capabilities.CapabilityTests.*;
import static com.alxarafe.erbas.capabilities.CapabilityRegistryTests.modules;

class CapabilityDiscoveryTests {
    private static final UserIdentity USER = new UserIdentity(1, "fixture@example.invalid", true, false);
    private static final String OTHER_PERMISSION = "extension.second";
    private final Map<String, Boolean> persisted = new HashMap<>();
    private final ModuleStateStore store = new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persisted.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persisted); }
    };
    private final ModuleRegistry modules = modules(OWNER);
    private final ModuleStateService states = new ModuleStateService(modules, store);
    private final CapabilityRegistry registry = new CapabilityRegistry(List.of(() -> List.of(
            capability(SECOND, List.of(PERMISSION, OTHER_PERMISSION)), capability(FIRST, List.of()))), modules, SCHEMAS);

    @Test void requiresAllPermissionsAndReevaluatesPolicyOnEveryDiscovery() {
        states.synchronize();
        var grants = new HashSet<>(Set.of(PERMISSION));
        var discovery = new CapabilityDiscovery(registry, states, List.of((user, permission) -> grants.contains(permission)));
        assertThat(discovery.effective(USER)).extracting(Capability::id).containsExactly(FIRST);
        grants.add(OTHER_PERMISSION);
        assertThat(discovery.effective(USER)).extracting(Capability::id).containsExactly(FIRST, SECOND);
        grants.clear();
        assertThat(discovery.effective(USER)).extracting(Capability::id).containsExactly(FIRST);
    }
    @Test void moduleDisableAndRestoreAreEffectiveWithoutRebuildingRegistry() {
        states.synchronize();
        var discovery = new CapabilityDiscovery(registry, states, List.of((user, permission) -> true));
        assertThat(discovery.effective(USER)).hasSize(2);
        states.disable(OWNER); assertThat(discovery.effective(USER)).isEmpty();
        states.enable(OWNER); assertThat(discovery.effective(USER)).hasSize(2);
    }
    @Test void absentModuleIsHiddenButMissingPersistedStateIsNotMasked() {
        var unavailable = new CapabilityDiscovery(registry, new ModuleStateService(modules(), store), List.of());
        assertThat(unavailable.effective(USER)).isEmpty();
        var missingState = new CapabilityDiscovery(registry, states, List.of());
        assertThatThrownBy(() -> missingState.effective(USER)).isInstanceOf(IllegalStateException.class);
    }
    @Test void unauthenticatedAndDisabledIdentitiesHaveNoEffectiveDeclarations() {
        var discovery = new CapabilityDiscovery(registry, states, List.of());
        assertThat(discovery.effective(null)).isEmpty();
        assertThat(discovery.effective(new UserIdentity(1, USER.email(), false, true))).isEmpty();
    }
}
