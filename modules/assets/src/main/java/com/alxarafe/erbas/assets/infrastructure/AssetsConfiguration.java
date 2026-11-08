package com.alxarafe.erbas.assets.infrastructure;

import java.util.List;
import java.util.Set;
import com.alxarafe.erbas.assets.*;
import com.alxarafe.erbas.assets.domain.AssetLifecycle;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.navigation.NavigationContribution;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AssetsConfiguration {
    /** Permission names exported for host security composition, without role inspection in Assets. */
    public static Set<String> permissions() { return AssetResource.PERMISSIONS; }
    @Bean AssetsModule assetsModule() { return new AssetsModule(); }
    @Bean ResourceRegistry.Source assetsResources() { return () -> List.of(AssetResource.definition()); }
    @Bean NavigationContribution.Source assetsNavigation() { return () -> List.of(AssetResource.navigation()); }
    @Bean JdbcAssetAdapter assetsAdapter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAssetAdapter(jdbc, new TransactionTemplate(manager));
    }
    @Bean AssetListReadPrimitive assetListReadPrimitive(ResourceRuntime runtime) {
        return new AssetListReadPrimitive(runtime);
    }
    @Bean ResourceActionExecutor completeAsset(JdbcAssetAdapter adapter) {
        return action(adapter, AssetResource.COMPLETE, AssetLifecycle.DRAFT, AssetLifecycle.READY);
    }
    @Bean ResourceActionExecutor retireAsset(JdbcAssetAdapter adapter) {
        return action(adapter, AssetResource.RETIRE, AssetLifecycle.READY, AssetLifecycle.RETIRED);
    }
    private ResourceActionExecutor action(JdbcAssetAdapter adapter, String name, AssetLifecycle from, AssetLifecycle to) {
        return new ResourceActionExecutor() {
            public String resourceId() { return AssetResource.RESOURCE; }
            public String actionName() { return name; }
            public void execute(UserIdentity user, List<Object> targets, java.util.Map<String, Object> parameters) {
                adapter.transition(targets.getFirst(), from, to);
            }
        };
    }
}
