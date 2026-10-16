package com.alxarafe.erbas;

import com.alxarafe.erbas.core.CoreConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent bootstrap for CORE MVC slices, without a host or functional module. */
@SpringBootApplication(scanBasePackages = {CoreConfiguration.HTTP_PACKAGE, CoreConfiguration.AUTH_PACKAGE,
        CoreConfiguration.MODULARITY_PACKAGE})
class CoreTestApplication { }
