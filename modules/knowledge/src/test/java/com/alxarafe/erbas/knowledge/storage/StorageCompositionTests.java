package com.alxarafe.erbas.knowledge.storage;

import java.util.List;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.infrastructure.*;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageCompositionTests {
    @Test void anExplicitProviderReplacesTheLocalDefaultWithoutChangingDocumentServices() {
        DocumentStorage provider = mock(DocumentStorage.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DocumentStorage.class, () -> provider);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(UserAdministration.class, () -> mock(UserAdministration.class));
            context.registerBean(ModuleRegistry.class, () -> new ModuleRegistry(List.of(() -> List.of(new KnowledgeModule()))));
            context.registerBean(ModuleStateService.class, () -> mock(ModuleStateService.class));
            context.register(KnowledgeConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(DocumentStorage.class)).hasSize(1).containsValue(provider);
            assertThat(context.getBean(JdbcDocumentStore.class)).isNotNull();
            assertThat(context.getBean(AuthorizedDocumentReader.class)).isNotNull();
        }
    }
}
