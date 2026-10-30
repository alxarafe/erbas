package com.alxarafe.erbas.core;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Non-disableable platform composition; functional modules are composed by the host. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {CoreConfiguration.HTTP_PACKAGE, CoreConfiguration.AUTH_PACKAGE,
        CoreConfiguration.MODULARITY_PACKAGE, CoreConfiguration.CAPABILITIES_PACKAGE, CoreConfiguration.READ_PACKAGE,
        CoreConfiguration.RESOURCES_PACKAGE, CoreConfiguration.NAVIGATION_PACKAGE})
public class CoreConfiguration {
    public static final String HTTP_PACKAGE = "com.alxarafe.erbas.adapter";
    public static final String AUTH_PACKAGE = "com.alxarafe.erbas.auth";
    public static final String MODULARITY_PACKAGE = "com.alxarafe.erbas.modularity";
    public static final String CAPABILITIES_PACKAGE = "com.alxarafe.erbas.capabilities";
    public static final String READ_PACKAGE = "com.alxarafe.erbas.read";
    public static final String RESOURCES_PACKAGE = "com.alxarafe.erbas.resources";
    public static final String NAVIGATION_PACKAGE = "com.alxarafe.erbas.navigation";
}
