package com.alxarafe.erbas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.modularity.ErbasModule;
import com.alxarafe.erbas.modularity.ModuleApi;
import com.alxarafe.erbas.modularity.ModuleDescriptor;
import com.alxarafe.erbas.modularity.ModuleDisabledException;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.http.ModuleEndpoint;
import com.alxarafe.erbas.modularity.http.ModuleController;
import com.alxarafe.erbas.modularity.http.ModuleEndpointInterceptor;
import com.alxarafe.erbas.modularity.persistence.JdbcModuleStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ModuleGatingIT.TestModules.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleGatingIT {
    private static final String ENDPOINT = "/module-gating-test/value";
    private static final String SECOND_ENDPOINT = "/module-gating-test/other";
    private static final String UNAVAILABLE_ENDPOINT = "/module-gating-test/unavailable";
    private static final String UNAVAILABLE_ID = "module-gating-test-unavailable";
    private static final String EMAIL = "module-gating-admin@example.test";
    private static final String PASSWORD = "module-gating-fixture-password";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    @Value("${local.server.port}") private int port;
    @Autowired private ModuleStateService states;
    @Autowired private TestModule module;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAuthenticationStore accounts;
    @Autowired private PasswordEncoder encoder;
    @Autowired private TestController controller;
    @Autowired private MockMvc mvc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final JsonMapper json = new JsonMapper();
    private long userId;
    private String token;

    @BeforeAll
    void createOnlyOwnedAccount() throws Exception {
        userId = accounts.createUser(EMAIL, encoder.encode(PASSWORD), true, true);
        token = login();
    }

    @AfterAll
    void removeOnlyOwnedFixtures() {
        jdbc.update("DELETE FROM auth_access_token WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_user WHERE id = ?", userId);
        jdbc.update("DELETE FROM " + JdbcModuleStateStore.TABLE + " WHERE " + JdbcModuleStateStore.MODULE_ID + " = ?", TestModule.ID);
        client.close();
    }

    @Test
    void realHttpGateIsDynamicAfterSecurityAndCoreRemainsAccessible() throws Exception {
        states.enable(TestModule.ID);
        int before = controller.calls.get();
        assertThat(get(ENDPOINT, token).statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(module.initializations.get()).isEqualTo(1);
        var migrations = jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        try {
            states.disable(TestModule.ID);
            var disabled = assertThatThrownBy(() -> states.requireEnabled(TestModule.ID))
                    .isInstanceOf(ModuleDisabledException.class);
            disabled.satisfies(failure -> assertThat(((ModuleDisabledException) failure).moduleId()).isEqualTo(TestModule.ID));
            assertDisabled(get(ENDPOINT, token));
            assertDisabled(get(SECOND_ENDPOINT, token));
            assertThat(controller.calls.get()).isEqualTo(before + 1);
            var anonymous = get(ENDPOINT, null);
            assertThat(anonymous.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(anonymous.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
            assertThat(get(ENDPOINT, "invalid-fixture-token").statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(get("/health", null).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get("/actuator/health", null).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get("/api/auth/me", token).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get("/api/users", token).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(get(ModuleController.MODULES_PATH, token).statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(login()).isNotBlank();
        } finally {
            states.enable(TestModule.ID);
        }
        assertThat(get(ENDPOINT, token).statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(get(SECOND_ENDPOINT, token).statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(module.initializations.get()).isEqualTo(1);
        assertThat(accounts.findUserById(userId)).isPresent();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class)).isEqualTo(migrations);
    }

    @Test
    void missingStateIsAnInternalErrorAndCannotMasqueradeAsDisabled() throws Exception {
        jdbc.update("DELETE FROM " + JdbcModuleStateStore.TABLE + " WHERE " + JdbcModuleStateStore.MODULE_ID + " = ?", TestModule.ID);
        try {
            assertThatThrownBy(() -> states.requireEnabled(TestModule.ID))
                    .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ModuleDisabledException.class);
            var response = get(ENDPOINT, token);
            assertThat(response.statusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(response.body()).doesNotContain(ModuleEndpointInterceptor.DISABLED_CODE);
            assertThatThrownBy(() -> mvc.perform(MockMvcRequestBuilders.get(ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        } finally {
            states.synchronize();
        }
    }

    @Test
    void unavailableModuleIsAnInternalErrorAndCannotMasqueradeAsDisabled() throws Exception {
        assertThatThrownBy(() -> states.requireEnabled(UNAVAILABLE_ID))
                .isInstanceOf(IllegalArgumentException.class).isNotInstanceOf(ModuleDisabledException.class);
        var response = get(UNAVAILABLE_ENDPOINT, token);
        assertThat(response.statusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.body()).doesNotContain(ModuleEndpointInterceptor.DISABLED_CODE);
        assertThatThrownBy(() -> mvc.perform(MockMvcRequestBuilders.get(UNAVAILABLE_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(get(UNAVAILABLE_ENDPOINT, null).statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private void assertDisabled(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(json.readTree(response.body())).isEqualTo(json.valueToTree(Map.of("code", ModuleEndpointInterceptor.DISABLED_CODE, "errors", java.util.List.of(Map.of("code", ModuleEndpointInterceptor.DISABLED_CODE)))));
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains(CacheControl.noStore().getHeaderValue());
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).isEmpty();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        var request = request(path).GET();
        if (bearer != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        var response = client.send(request("/api/auth/login").header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        return json.readTree(response.body()).get("accessToken").asString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(REQUEST_TIMEOUT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestModules {
        @Bean TestModule testModule() { return new TestModule(); }
        @Bean TestController testController() { return new TestController(); }
        @Bean UnavailableController unavailableController() { return new UnavailableController(); }
    }

    static final class TestModule implements ErbasModule {
        static final String ID = "module-gating-test";
        final AtomicInteger initializations = new AtomicInteger();
        @Override public ModuleDescriptor descriptor() { return new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of()); }
        @Override public void initialize() { initializations.incrementAndGet(); }
    }

    @TestComponent
    @RestController
    @ModuleEndpoint(TestModule.ID)
    static class TestController {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping({ENDPOINT, SECOND_ENDPOINT})
        Map<String, String> value() { calls.incrementAndGet(); return Map.of("value", "available"); }
    }

    @TestComponent
    @RestController
    @ModuleEndpoint(UNAVAILABLE_ID)
    static class UnavailableController {
        @GetMapping(UNAVAILABLE_ENDPOINT)
        String value() { return "must-not-execute"; }
    }
}
