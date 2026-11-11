package com.alxarafe.erbas.knowledge.storage.infrastructure;

import com.alxarafe.erbas.knowledge.storage.KnowledgeModule;
import com.alxarafe.erbas.knowledge.*;
import com.alxarafe.erbas.knowledge.storage.application.AuthorizedDocumentReader;
import com.alxarafe.erbas.knowledge.storage.application.DocumentStorage;
import com.alxarafe.erbas.knowledge.storage.application.DocumentProcessor;
import com.alxarafe.erbas.knowledge.storage.application.DocumentProcessingService;
import com.alxarafe.erbas.knowledge.storage.application.ProcessingLimits;
import com.alxarafe.erbas.knowledge.storage.application.TextDocumentProcessor;
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
    public static final String PROCESSING_PREFIX = "erbas.knowledge.processing.";
    public static final String ORIGINAL_LIMIT_PROPERTY = PROCESSING_PREFIX + "max-original-bytes";
    public static final String NORMALIZED_LIMIT_PROPERTY = PROCESSING_PREFIX + "max-normalized-bytes";
    public static final String PAGE_LIMIT_PROPERTY = PROCESSING_PREFIX + "max-pages";
    public static final String TIMEOUT_PROPERTY = PROCESSING_PREFIX + "timeout";
    public static final String TEXT_ENABLED_PROPERTY = PROCESSING_PREFIX + "text.enabled";
    @Bean KnowledgeModule knowledgeModule() { return new KnowledgeModule(); }
    @Bean ProcessingLimits processingLimits(Environment environment) {
        var defaults = ProcessingLimits.DEFAULT;
        return new ProcessingLimits(environment.getProperty(ORIGINAL_LIMIT_PROPERTY, Integer.class, defaults.originalBytes()),
                environment.getProperty(NORMALIZED_LIMIT_PROPERTY, Integer.class, defaults.normalizedBytes()),
                environment.getProperty(PAGE_LIMIT_PROPERTY, Integer.class, defaults.pages()),
                java.time.Duration.parse(environment.getProperty(TIMEOUT_PROPERTY, defaults.timeout().toString())));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = TEXT_ENABLED_PROPERTY, matchIfMissing = true)
    TextDocumentProcessor textDocumentProcessor() { return new TextDocumentProcessor(); }
    @Bean DocumentProcessingService documentProcessingService(ProcessingLimits limits, List<DocumentProcessor> processors) {
        return new DocumentProcessingService(limits, processors);
    }
    @Bean @ConditionalOnMissingBean(DocumentStorage.class)
    DocumentStorage documentStorage(Environment environment) {
        return new LocalDocumentStorage(Path.of(environment.getProperty(STORAGE_ROOT_PROPERTY, LocalDocumentStorage.DEFAULT_ROOT)));
    }
    @Bean JdbcDocumentStore documentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, DocumentStorage storage, ProcessingLimits limits) {
        return new JdbcDocumentStore(jdbc, transactions, storage, limits);
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
