package com.alxarafe.erbas.knowledge.storage.infrastructure;

import com.alxarafe.erbas.knowledge.storage.KnowledgeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {
    @Bean KnowledgeModule knowledgeModule() { return new KnowledgeModule(); }
    @Bean JdbcDocumentStore documentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcDocumentStore(jdbc, transactions);
    }
}
