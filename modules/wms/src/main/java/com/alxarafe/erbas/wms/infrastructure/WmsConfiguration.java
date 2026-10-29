package com.alxarafe.erbas.wms.infrastructure;

import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.WmsCapabilities;
import com.alxarafe.erbas.wms.application.FamilyAssociations;
import com.alxarafe.erbas.wms.application.ItemFamilyCatalogue;
import com.alxarafe.erbas.wms.http.ItemFamilyController;
import com.alxarafe.erbas.wms.http.FamilyAssociationController;
import com.alxarafe.erbas.wms.application.UomCatalogue;
import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import com.alxarafe.erbas.wms.http.UomController;
import com.alxarafe.erbas.wms.http.StorageAttributeController;
import com.alxarafe.erbas.security.PlatformAuthorities;
import com.alxarafe.erbas.wms.http.CatalogueExceptionHandler;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Import({UomController.class, StorageAttributeController.class, ItemFamilyController.class, FamilyAssociationController.class, CatalogueExceptionHandler.class})
public class WmsConfiguration {
    @Bean public WmsModule wmsModule() { return new WmsModule(); }
    @Bean public WmsCapabilities wmsCapabilities() { return new WmsCapabilities(); }
    @Bean public JdbcUomStore uomStore(JdbcTemplate jdbc) { return new JdbcUomStore(jdbc); }
    @Bean public UomCatalogue uomCatalogue(JdbcUomStore store) { return new UomCatalogue(store); }

    @Bean public JdbcStorageAttributeStore storageAttributeStore(JdbcTemplate jdbc) { return new JdbcStorageAttributeStore(jdbc); }
    @Bean public StorageAttributeCatalogue storageAttributeCatalogue(JdbcStorageAttributeStore store) { return new StorageAttributeCatalogue(store); }

    @Bean public JdbcItemFamilyStore itemFamilyStore(JdbcTemplate jdbc) { return new JdbcItemFamilyStore(jdbc); }
    @Bean public ItemFamilyCatalogue itemFamilyCatalogue(JdbcItemFamilyStore store) { return new ItemFamilyCatalogue(store); }
    @Bean public JdbcFamilyAssociationStore familyAssociationStore(JdbcTemplate jdbc) { return new JdbcFamilyAssociationStore(jdbc); }
    @Bean public FamilyAssociations familyAssociations(JdbcFamilyAssociationStore store) { return new FamilyAssociations(store); }

    @Bean
    public Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> wmsAuthorization() {
        return authorize -> authorize.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(UomController.UOMS_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(StorageAttributeController.STORAGE_ATTRIBUTES_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(ItemFamilyController.ITEM_FAMILIES_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(FamilyAssociationController.ASSOCIATIONS_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(FamilyAssociationController.SINGLE_PATH))
                .hasAuthority(PlatformAuthorities.ADMINISTRATOR);
    }
}
