package com.alxarafe.erbas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ErbasApplicationTests {

    @Test
    void applicationContextLoads() {
        assertThat(true).isTrue();
    }
}
