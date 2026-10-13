package com.alxarafe.erbas;

import org.junit.jupiter.api.Test;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ErbasApplicationTests {
    @Autowired private ModuleRegistry modules;

    @Test
    void applicationContextLoads() {
        assertThat(modules.modules()).isEmpty();
    }
}
