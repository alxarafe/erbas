package com.alxarafe.erbas.wms;

import com.alxarafe.erbas.auth.application.UserIdentity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WmsCapabilitiesTests {
    private final WmsCapabilities provider = new WmsCapabilities();
    @Test void declarationsExactlyMatchPublishedV090ProviderFixture() throws Exception {
        var json = new JsonMapper();
        try (var expected = getClass().getResourceAsStream("/capabilities-v0.9.0.json")) {
            assertThat(expected).isNotNull();
            tools.jackson.databind.JsonNode actual = json.valueToTree(provider.capabilities());
            assertThat(actual).isEqualTo(json.readTree(expected));
        }
    }
    @Test void bridgeGrantsOnlyDeclaredWmsReadPermissionsToCurrentEnabledAdministrator() {
        var admin = new UserIdentity(1, "admin@example.invalid", true, true);
        var normal = new UserIdentity(2, "normal@example.invalid", true, false);
        var disabled = new UserIdentity(1, admin.email(), false, true);
        for (var declaration : provider.capabilities()) {
            for (var permission : declaration.requiredPermissions()) {
                assertThat(provider.granted(admin, permission)).isTrue();
                assertThat(provider.granted(normal, permission)).isFalse();
                assertThat(provider.granted(disabled, permission)).isFalse();
                assertThat(provider.granted(null, permission)).isFalse();
            }
        }
        assertThat(provider.granted(admin, "unknown.permission")).isFalse();
        assertThat(provider.granted(admin, com.alxarafe.erbas.security.PlatformAuthorities.ADMINISTRATOR)).isFalse();
    }
}
