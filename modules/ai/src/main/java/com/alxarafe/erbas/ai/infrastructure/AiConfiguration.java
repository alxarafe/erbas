package com.alxarafe.erbas.ai.infrastructure;

import com.alxarafe.erbas.ai.*;
import com.alxarafe.erbas.ai.http.AssistantController;
import com.alxarafe.erbas.ai.http.AssistantAuthenticationAuditFilter;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.capabilities.CapabilityDiscovery;
import com.alxarafe.erbas.capabilities.CapabilityInvocation;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.read.ReadPrimitiveRouter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import static com.alxarafe.erbas.modularity.ConformanceModulesConfiguration.VALIDATION_PROFILE;

@Configuration(proxyBeanMethods = false)
@Import(AssistantController.class)
public class AiConfiguration {
    @Bean public AiModule aiModule() { return new AiModule(); }
    @Bean public AiAssistantPermission aiAssistantPermission() {
        return user -> user != null && user.enabled() && user.admin();
    }
    @Bean @Profile("!" + VALIDATION_PROFILE)
    public AiAssistantProvider unavailableAssistantProvider() { return new UnavailableAssistantProvider(); }
    @Bean @Profile(VALIDATION_PROFILE)
    public AiAssistantProvider deterministicAssistantProvider(MessageSource messages) {
        return new DeterministicAssistantProvider(messages);
    }
    @Bean public AiAssistantAuditSink aiAssistantAuditSink() {
        var logger = org.slf4j.LoggerFactory.getLogger(AiAssistantAuditSink.class);
        return entry -> logger.info("AI assistant audit: {}", entry);
    }
    @Bean public AssistantInvocation assistantInvocation(AiAssistantPermission permission, ModuleStateService states,
            CapabilityDiscovery discovery, CapabilityInvocation capabilities, ReadPrimitiveRouter primitives, UserAdministration users,
            AiAssistantProvider provider, AiAssistantAuditSink audit) {
        return new AssistantInvocation(permission, states, discovery, capabilities, primitives,
                user -> users.find(user.getName()).orElse(null), provider, audit);
    }
    @Bean public FilterRegistrationBean<AssistantAuthenticationAuditFilter> assistantAuthenticationAudit(AssistantInvocation assistant) {
        var registration = new FilterRegistrationBean<>(new AssistantAuthenticationAuditFilter(assistant));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
