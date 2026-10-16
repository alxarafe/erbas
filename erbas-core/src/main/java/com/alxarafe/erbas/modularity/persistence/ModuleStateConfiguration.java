package com.alxarafe.erbas.modularity.persistence;

import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class ModuleStateConfiguration {
    @Bean
    public ModuleStateStore moduleStateStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcModuleStateStore(jdbc, transactions);
    }

    @Bean
    public ModuleStateService moduleStateService(ModuleRegistry registry, ModuleStateStore store) {
        return new ModuleStateService(registry, store);
    }
}
