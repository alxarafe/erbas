package com.alxarafe.erbas;

import org.springframework.boot.SpringApplication;
import com.alxarafe.erbas.core.CoreConfiguration;
import com.alxarafe.erbas.knowledge.storage.infrastructure.KnowledgeConfiguration;
import com.alxarafe.erbas.wms.infrastructure.WmsConfiguration;
import com.alxarafe.erbas.ai.infrastructure.AiConfiguration;
import com.alxarafe.erbas.assets.infrastructure.AssetsConfiguration;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import com.alxarafe.erbas.capabilities.CapabilitySchemaCatalog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({CoreConfiguration.class, WmsConfiguration.class, AiConfiguration.class, AssetsConfiguration.class, KnowledgeConfiguration.class})
public class ErbasApplication {
    @Bean
    public FunctionalPermissionPolicy assetAdministratorGrants() {
        return (user, permission) -> user != null && user.enabled() && user.admin() && AssetsConfiguration.permissions().contains(permission);
    }
    @Bean
    public CapabilitySchemaCatalog capabilitySchemas() throws IOException {
        var resource = ErbasApplication.class.getResourceAsStream("/contracts/capability-schema-components.txt");
        if (resource == null) throw new IllegalStateException("Published capability schema index is missing");
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return new CapabilitySchemaCatalog(reader.lines().collect(Collectors.toSet()));
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(ErbasApplication.class, args);
    }
}
