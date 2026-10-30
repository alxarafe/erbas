package com.alxarafe.erbas.read;

import java.util.List;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReadPrimitiveConfiguration {
    @Bean public ReadPrimitiveRegistry readPrimitiveRegistry(List<ReadPrimitiveExecutor> executors, ModuleRegistry modules) {
        return new ReadPrimitiveRegistry(executors, modules);
    }
    @Bean public ReadPrimitiveAuditSink readPrimitiveAuditSink() {
        var logger = org.slf4j.LoggerFactory.getLogger(ReadPrimitiveAuditSink.class);
        return entry -> logger.info("READ primitive audit: {}", entry);
    }
    @Bean public ReadPrimitiveRouter readPrimitiveRouter(ReadPrimitiveRegistry registry, ModuleStateService states,
            UserAdministration users, List<FunctionalPermissionPolicy> policies, ReadPrimitiveAuditSink audit) {
        return new ReadPrimitiveRouter(registry, states, id -> users.find(Long.toString(id)), policies, audit);
    }
}
