package com.alxarafe.erbas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ErbasApplicationTests {

    @Test
    void applicationContextLoads() {
        assertThat(true).isTrue();
    }
}
