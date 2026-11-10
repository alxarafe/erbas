package com.alxarafe.erbas.knowledge.storage.infrastructure;

import com.alxarafe.erbas.knowledge.storage.KnowledgeModule;
import com.alxarafe.erbas.knowledge.*;
import com.alxarafe.erbas.knowledge.storage.application.AuthorizedDocumentReader;
import com.alxarafe.erbas.knowledge.storage.application.DocumentStorage;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import java.util.List;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {
    public static final String STORAGE_ROOT_PROPERTY = "erbas.knowledge.storage.root";
    @Bean KnowledgeModule knowledgeModule() { return new KnowledgeModule(); }
    @Bean @ConditionalOnMissingBean(DocumentStorage.class)
    DocumentStorage documentStorage(Environment environment) {
        return new LocalDocumentStorage(Path.of(environment.getProperty(STORAGE_ROOT_PROPERTY, LocalDocumentStorage.DEFAULT_ROOT)));
    }
    @Bean JdbcDocumentStore documentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, DocumentStorage storage) {
        return new JdbcDocumentStore(jdbc, transactions, storage);
    }

    @Bean AuthorizedDocumentReader authorizedDocumentReader(JdbcDocumentStore store, ModuleRegistry modules,
            ModuleStateService states, UserAdministration users, List<FunctionalPermissionPolicy> permissions,
            List<DocumentVisibilityPolicy> visibility, List<DocumentAccessPolicy> policies) {
        // No visibility authority means no disclosure; every registered authority can veto.
        var authorization = new DocumentAuthorization(modules, states, id -> users.find(Long.toString(id)), permissions,
                (user, document) -> !visibility.isEmpty()
                        && visibility.stream().allMatch(policy -> policy.visible(user, document)), policies);
        return new AuthorizedDocumentReader(store, authorization);
    }
}
