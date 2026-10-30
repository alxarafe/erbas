package com.alxarafe.erbas;

import org.junit.jupiter.api.Test;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.ai.AiModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ErbasApplicationTests {
    @Autowired private ModuleRegistry modules;
    // This native context test has no database; real state synchronization is covered by ITs.
    @MockitoBean private ModuleStateService states;

    @Test
    void applicationContextLoads() {
        assertThat(modules.modules().stream().map(module -> module.descriptor().id())).containsExactly(AiModule.ID, WmsModule.ID);
    }
}
