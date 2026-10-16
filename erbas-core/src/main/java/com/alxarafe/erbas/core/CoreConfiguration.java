package com.alxarafe.erbas.core;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Non-disableable platform composition; functional modules are composed by the host. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {CoreConfiguration.HTTP_PACKAGE, CoreConfiguration.AUTH_PACKAGE,
        CoreConfiguration.MODULARITY_PACKAGE})
public class CoreConfiguration {
    public static final String HTTP_PACKAGE = "com.alxarafe.erbas.adapter";
    public static final String AUTH_PACKAGE = "com.alxarafe.erbas.auth";
    public static final String MODULARITY_PACKAGE = "com.alxarafe.erbas.modularity";
}
