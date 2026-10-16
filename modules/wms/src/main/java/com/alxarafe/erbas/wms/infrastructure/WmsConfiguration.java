package com.alxarafe.erbas.wms.infrastructure;

import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.UomCatalogue;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.security.PlatformAuthorities;
import com.alxarafe.erbas.wms.http.UomExceptionHandler;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Import({UomController.class, UomExceptionHandler.class})
public class WmsConfiguration {
    @Bean public WmsModule wmsModule() { return new WmsModule(); }
    @Bean public JdbcUomStore uomStore(JdbcTemplate jdbc) { return new JdbcUomStore(jdbc); }
    @Bean public UomCatalogue uomCatalogue(JdbcUomStore store) { return new UomCatalogue(store); }

    @Bean
    public Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> wmsAuthorization() {
        return authorize -> authorize.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(UomController.UOMS_PATH))
                .hasAuthority(PlatformAuthorities.ADMINISTRATOR);
    }
}
