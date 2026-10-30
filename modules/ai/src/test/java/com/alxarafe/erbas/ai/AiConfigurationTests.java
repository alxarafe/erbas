package com.alxarafe.erbas.ai;

import java.util.List;
import com.alxarafe.erbas.ai.infrastructure.*;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.*;
import com.alxarafe.erbas.modularity.ModuleStateService;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import static com.alxarafe.erbas.modularity.ConformanceModulesConfiguration.VALIDATION_PROFILE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiConfigurationTests {
    @Test void normalCompositionUsesSafeUnavailableProvider() {
        try (var context = context()) {
            assertThat(context.getBean(AiAssistantProvider.class)).isInstanceOf(UnavailableAssistantProvider.class);
            assertThatThrownBy(() -> context.getBean(AiAssistantProvider.class).respond("hello", List.of(), List.of()))
                    .isInstanceOf(AiAssistantProvider.Unavailable.class);
            assertThat(context.getBean(AiModule.class).descriptor().id()).isEqualTo(AiModule.ID);
        }
    }
    @Test void onlyExplicitValidationCompositionSelectsDeterministicProvider() {
        try (var context = context(VALIDATION_PROFILE)) {
            assertThat(context.getBean(AiAssistantProvider.class)).isInstanceOf(DeterministicAssistantProvider.class);
            assertThat(context.getBeansOfType(AiAssistantProvider.class)).hasSize(1);
        }
    }
    @Test void permissionBridgeGrantsOnlyEnabledAdministratorAndNoBusinessPermissionPolicy() {
        var policy = new AiConfiguration().aiAssistantPermission();
        assertThat(policy.granted(new UserIdentity(1, "fixture@example.test", true, true))).isTrue();
        assertThat(policy.granted(new UserIdentity(1, "fixture@example.test", true, false))).isFalse();
        assertThat(policy.granted(new UserIdentity(1, "fixture@example.test", false, true))).isFalse();
        assertThat(policy.granted(null)).isFalse();
        assertThat(policy).isNotInstanceOf(CapabilityPermissionPolicy.class);
    }
    private AnnotationConfigApplicationContext context(String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.registerBean(ModuleStateService.class, () -> mock(ModuleStateService.class));
        context.registerBean(CapabilityDiscovery.class, () -> mock(CapabilityDiscovery.class));
        context.registerBean(CapabilityInvocation.class, () -> mock(CapabilityInvocation.class));
        context.registerBean(com.alxarafe.erbas.read.ReadPrimitiveRouter.class,
                () -> mock(com.alxarafe.erbas.read.ReadPrimitiveRouter.class));
        context.registerBean(UserAdministration.class, () -> mock(UserAdministration.class));
        context.registerBean(MessageSource.class, StaticMessageSource::new);
        context.register(AiConfiguration.class); context.refresh();
        return context;
    }
}
