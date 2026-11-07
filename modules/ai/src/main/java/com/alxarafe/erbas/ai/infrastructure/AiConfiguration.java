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
    public static final String PROTOCOL_PROPERTY = "erbas.ai.provider.protocol";
    public static final String ENDPOINT_PROPERTY = "erbas.ai.provider.endpoint";
    public static final String MODEL_PROPERTY = "erbas.ai.provider.model";
    public static final String CREDENTIAL_PROPERTY = "erbas.ai.provider.credential";
    public static final String TIMEOUT_PROPERTY = "erbas.ai.provider.timeout-seconds";
    public static final String TRUSTED_LOCAL_PROPERTY = "erbas.ai.provider.trusted-local";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String OLLAMA_NATIVE = "ollama-native";
    @Bean public AiModule aiModule() { return new AiModule(); }
    @Bean public AiAssistantPermission aiAssistantPermission() {
        return user -> user != null && user.enabled() && user.admin();
    }
    @Bean @Profile("!" + VALIDATION_PROFILE)
    public AiAssistantProvider normalAssistantProvider(org.springframework.core.env.Environment environment) {
        return configuredProvider(environment);
    }
    public static AiAssistantProvider configuredProvider(org.springframework.core.env.Environment environment) {
        String protocol = environment.getProperty(PROTOCOL_PROPERTY);
        // An existing native endpoint is an explicit trust decision under the legacy configuration.
        if (protocol == null || protocol.isBlank()) {
            if (protocol != null && !protocol.isEmpty()) return new UnavailableAssistantProvider();
            for (String property : java.util.List.of(ENDPOINT_PROPERTY, MODEL_PROPERTY, CREDENTIAL_PROPERTY,
                    TIMEOUT_PROPERTY, TRUSTED_LOCAL_PROPERTY)) {
                if (!environment.getProperty(property, "").isEmpty()) return new UnavailableAssistantProvider();
            }
            return OllamaAssistantProvider.configured(environment);
        }
        try {
            if (!java.util.Set.of(OPENAI_COMPATIBLE, OLLAMA_NATIVE).contains(protocol)) return new UnavailableAssistantProvider();
            boolean nativeProtocol = OLLAMA_NATIVE.equals(protocol);
            String endpoint = environment.getProperty(ENDPOINT_PROPERTY, "").strip();
            String model = environment.getProperty(MODEL_PROPERTY, "").strip();
            if (nativeProtocol && endpoint.isEmpty()) endpoint = environment.getProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, "").strip();
            if (nativeProtocol && model.isEmpty()) model = environment.getProperty(OllamaAssistantProvider.MODEL_PROPERTY, "").strip();
            String timeoutValue = environment.getProperty(TIMEOUT_PROPERTY, "");
            if (timeoutValue.isEmpty()) timeoutValue =
                    nativeProtocol ? environment.getProperty(OllamaAssistantProvider.TIMEOUT_SECONDS_PROPERTY,
                            Long.toString(OllamaAssistantProvider.DEFAULT_REQUEST_TIMEOUT.toSeconds()))
                            : Long.toString(OllamaAssistantProvider.DEFAULT_REQUEST_TIMEOUT.toSeconds());
            var timeout = java.time.Duration.ofSeconds(Long.parseLong(timeoutValue.strip()));
            String trust = environment.getProperty(TRUSTED_LOCAL_PROPERTY, "false");
            if (trust.isEmpty()) trust = "false";
            if (!java.util.Set.of("true", "false").contains(trust)) return new UnavailableAssistantProvider();
            boolean trustedLocal = Boolean.parseBoolean(trust);
            String credential = environment.getProperty(CREDENTIAL_PROPERTY, "");
            if (credential.isEmpty() && !trustedLocal) return new UnavailableAssistantProvider();
            var uri = java.net.URI.create(endpoint);
            return nativeProtocol ? new OllamaAssistantProvider(uri, model, timeout, credential, trustedLocal)
                    : new OpenAiCompatibleAssistantProvider(uri, model, timeout, credential, trustedLocal);
        } catch (IllegalArgumentException invalid) { return new UnavailableAssistantProvider(); }
    }
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
