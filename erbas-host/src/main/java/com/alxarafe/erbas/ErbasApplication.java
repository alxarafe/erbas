package com.alxarafe.erbas;

import org.springframework.boot.SpringApplication;
import com.alxarafe.erbas.core.CoreConfiguration;
import com.alxarafe.erbas.wms.infrastructure.WmsConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({CoreConfiguration.class, WmsConfiguration.class})
public class ErbasApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErbasApplication.class, args);
    }
}
