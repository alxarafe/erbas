package com.alxarafe.erbas;

import org.springframework.boot.SpringApplication;
import com.alxarafe.erbas.core.CoreConfiguration;
import com.alxarafe.erbas.wms.infrastructure.WmsConfiguration;
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
@Import({CoreConfiguration.class, WmsConfiguration.class})
public class ErbasApplication {
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
