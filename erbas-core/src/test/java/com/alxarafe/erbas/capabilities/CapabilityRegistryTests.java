package com.alxarafe.erbas.capabilities;

import java.util.List;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.capabilities.CapabilityTests.*;

class CapabilityRegistryTests {
    static ModuleRegistry modules(String... ids) {
        return new ModuleRegistry(List.of(() -> java.util.Arrays.stream(ids).map(id -> new ErbasModule() {
            public ModuleDescriptor descriptor() { return new ModuleDescriptor(id, ModuleApi.CURRENT_VERSION, List.of()); }
            public void initialize() { }
        }).toList()));
    }
    @Test void snapshotsProvidersWithGlobalDeterministicOrdering() {
        var values = new java.util.ArrayList<>(List.of(capability(SECOND, List.of()), capability(FIRST, List.of())));
        var registry = new CapabilityRegistry(List.of(() -> values), modules(OWNER), SCHEMAS);
        values.clear();
        assertThat(registry.capabilities()).extracting(Capability::id).containsExactly(FIRST, SECOND);
        assertThatThrownBy(() -> registry.capabilities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void acceptsEmptyProviderSet() {
        assertThat(new CapabilityRegistry(List.of(), modules(), SCHEMAS).capabilities()).isEmpty();
    }
    @Test void rejectsDuplicateIdsWithinProvider() {
        var value = capability(FIRST, List.of());
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(() -> List.of(value, value)), modules(OWNER), SCHEMAS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(FIRST);
    }
    @Test void rejectsDuplicateIdsAcrossProvidersEvenWithDifferentVersion() {
        var value = capability(FIRST, List.of());
        String otherOwner = "another.provider";
        var conflicting = new Capability(FIRST, otherOwner, "2.0.0", value.name(), value.description(), value.effect(), INPUT, OUTPUT, List.of());
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(() -> List.of(value), () -> List.of(conflicting)), modules(OWNER, otherOwner), SCHEMAS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(FIRST);
    }
    @Test void rejectsOwnerAbsentFromModuleRegistry() {
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(() -> List.of(capability(FIRST, List.of()))), modules(), SCHEMAS))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingInputAndOutputSchemaTargets() {
        for (var schemas : List.of(new CapabilitySchemaCatalog(java.util.Set.of("Input")),
                new CapabilitySchemaCatalog(java.util.Set.of("Output")))) {
            assertThatThrownBy(() -> new CapabilityRegistry(List.of(() -> List.of(capability(FIRST, List.of()))), modules(OWNER), schemas))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void invalidSpringRegistrationAbortsContextStartup() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ModuleRegistry.class, () -> modules(OWNER));
            context.registerBean(CapabilitySchemaCatalog.class, () -> SCHEMAS);
            context.registerBean("one", CapabilityProvider.class, () -> () -> List.of(capability(FIRST, List.of())));
            context.registerBean("two", CapabilityProvider.class, () -> () -> List.of(capability(FIRST, List.of())));
            context.register(CapabilityConfiguration.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
