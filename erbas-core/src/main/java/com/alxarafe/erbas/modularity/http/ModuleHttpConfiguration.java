package com.alxarafe.erbas.modularity.http;

import com.alxarafe.erbas.modularity.ModuleStateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ModuleHttpConfiguration {
    @Bean
    public WebMvcConfigurer moduleEndpoints(ModuleStateService states) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ModuleEndpointInterceptor(states));
            }
        };
    }
}
