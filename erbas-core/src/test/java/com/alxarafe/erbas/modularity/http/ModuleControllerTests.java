package com.alxarafe.erbas.modularity.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.auth.infrastructure.OpaqueAccessTokens;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ModuleController.class)
@Import({AuthenticationConfiguration.class, ModuleExceptionHandler.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ModuleControllerTests {
    private static final String FIRST = "A-fixture";
    private static final String SECOND = "Z-fixture";
    private static final String CHILD = "a-fixture";
    private static final String UNKNOWN = "unavailable-fixture";
    private static final String ADMIN_TOKEN = "module-admin-fixture";
    private static final String USER_TOKEN = "module-user-fixture";
    private static final String BEARER_SCHEME = "Bearer";
    @Autowired private MockMvc mvc;
    @MockitoBean private ModuleRegistry registry;
    @MockitoBean private ModuleStateService states;
    @MockitoBean private JdbcAuthenticationStore accounts;
    @MockitoBean private OpaqueAccessTokens tokens;
    @MockitoBean private PasswordEncoder encoder;
    private final JsonMapper json = new JsonMapper();

    @BeforeEach
    void availableModulesAndBearerIdentities() {
        // Deliberately reverse insertion order and dependency order. The real
        // registry supplies natural, case-sensitive order to the HTTP adapter.
        var actual = new ModuleRegistry(List.of(() -> List.of(
                module(CHILD, List.of(SECOND, FIRST)), module(SECOND, List.of()), module(FIRST, List.of()))));
        when(registry.modules()).thenReturn(actual.modules());
        when(states.isEnabled(anyString())).thenReturn(true);
        when(tokens.findIdentity(ADMIN_TOKEN)).thenReturn(Optional.of(new UserIdentity(1, "admin@example.test", true, true)));
        when(tokens.findIdentity(USER_TOKEN)).thenReturn(Optional.of(new UserIdentity(2, "user@example.test", true, false)));
        clearInvocations(registry, states, accounts, encoder);
    }

    @Test
    void adminListHasClosedShapeSortedDependenciesAndPaginationDefaults() throws Exception {
        send(get(ModuleController.MODULES_PATH), ADMIN_TOKEN).andExpect(status().isOk())
                .andExpect(content().json(collection(List.of(representation(FIRST, true),
                        representation(SECOND, true), representation(CHILD, true)), "0", PagedCollection.DEFAULT_LIMIT),
                        JsonCompareMode.STRICT));
    }

    @Test
    void zeroAvailableModulesReturnAnEmptyClosedEnvelopeWithoutReadingPersistedState() throws Exception {
        when(registry.modules()).thenReturn(List.of());
        send(get(ModuleController.MODULES_PATH), ADMIN_TOKEN).andExpect(status().isOk())
                .andExpect(content().json(json.writeValueAsString(Map.of("items", List.of(), "offset", 0,
                        "limit", PagedCollection.DEFAULT_LIMIT, "total", 0,
                        "order", List.of(Map.of("field", "id", "direction", "asc")))), JsonCompareMode.STRICT));
        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @CsvSource({"0,1,A-fixture", "1,1,Z-fixture", "2,1,a-fixture"})
    void registryOrderingPrecedesPagination(String offset, int limit, String expectedId) throws Exception {
        send(get(ModuleController.MODULES_PATH).param("offset", offset).param("limit", Integer.toString(limit)), ADMIN_TOKEN)
                .andExpect(status().isOk())
                .andExpect(content().json(collection(List.of(representation(expectedId, true)), offset, limit), JsonCompareMode.STRICT));
        verify(states).isEnabled(expectedId);
        verifyNoMoreInteractions(states);
    }

    @Test
    void maximumLimitIsCapacityNotReturnedItemCount() throws Exception {
        send(get(ModuleController.MODULES_PATH).param("limit", Integer.toString(PagedCollection.MAXIMUM_LIMIT)), ADMIN_TOKEN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(PagedCollection.MAXIMUM_LIMIT))
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items.length()").value(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "100", "9223372036854775808"})
    void atOrBeyondTotalPreservesOffsetWithoutOverflow(String offset) throws Exception {
        send(get(ModuleController.MODULES_PATH).param("offset", offset), ADMIN_TOKEN).andExpect(status().isOk())
                .andExpect(content().json(collection(List.of(), offset, PagedCollection.DEFAULT_LIMIT), JsonCompareMode.STRICT));
        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset=-1", "offset=abc", "offset=1.5", "offset=", "limit=0",
            "limit=abc", "limit=1.5", "limit=", "limit=999999999999999999999"})
    void invalidPaginationDoesNotReadModules(String query) throws Exception {
        var parts = query.split("=", -1);
        send(get(ModuleController.MODULES_PATH).param(parts[0], parts[1]), ADMIN_TOKEN)
                .andExpect(status().isBadRequest()).andExpect(content().json(error(ModuleController.INVALID_REQUEST), JsonCompareMode.STRICT));
        verifyNoInteractions(registry, states);
    }

    @Test
    void limitAboveTheContractMaximumIsRejectedWithoutClamping() throws Exception {
        send(get(ModuleController.MODULES_PATH).param("limit", Integer.toString(PagedCollection.MAXIMUM_LIMIT + 1)), ADMIN_TOKEN)
                .andExpect(status().isBadRequest()).andExpect(content().json(error(ModuleController.INVALID_REQUEST), JsonCompareMode.STRICT));
        verifyNoInteractions(registry, states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-fixture", USER_TOKEN})
    void securityPrecedesPaginationBodyAndUnavailableLookup(String bearer) throws Exception {
        for (var request : List.of(get(ModuleController.MODULES_PATH).param("limit", "invalid"),
                patch(ModuleController.MODULES_PATH + "/" + UNKNOWN).contentType(MediaType.APPLICATION_JSON).content("{"),
                patch(ModuleController.MODULES_PATH + "/" + FIRST).contentType(MediaType.APPLICATION_JSON).content(enabled(false)))) {
            var result = mvc.perform(bearer.isEmpty() ? request : request.header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + " " + bearer))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
            if (bearer.equals(USER_TOKEN)) {
                result.andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                        .andExpect(content().json(error("forbidden"), JsonCompareMode.STRICT));
            } else {
                result.andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_SCHEME))
                        .andExpect(content().json(error("unauthorized"), JsonCompareMode.STRICT));
            }
        }
        verifyNoInteractions(registry, states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "[]", "null", "true", "42", "\"scalar\"", "{}",
            "{\"extra\":true}", "{\"enabled\":null}", "{\"enabled\":0}", "{\"enabled\":\"false\"}",
            "{\"enabled\":[]}", "{\"enabled\":false,\"extra\":true}",
            "{\"enabled\":true,\"enabled\":false}", "{\"enabled\":true} {}"})
    void invalidClosedBodyNeverPerformsLookupOrMutation(String body) throws Exception {
        send(patch(ModuleController.MODULES_PATH + "/" + FIRST).contentType(MediaType.APPLICATION_JSON).content(body), ADMIN_TOKEN)
                .andExpect(status().isBadRequest()).andExpect(content().json(error(ModuleController.INVALID_REQUEST), JsonCompareMode.STRICT));
        verifyNoInteractions(registry, states);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void patchUsesStateAuthorityAndReturnsTheCompletePersistedRepresentation(boolean enabled) throws Exception {
        when(states.isEnabled(CHILD)).thenReturn(enabled);
        send(patch(ModuleController.MODULES_PATH + "/" + CHILD).contentType(MediaType.APPLICATION_JSON).content(enabled(enabled)), ADMIN_TOKEN)
                .andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(representation(CHILD, enabled)), JsonCompareMode.STRICT));
        if (enabled) verify(states).enable(CHILD);
        else verify(states).disable(CHILD);
        verify(states).isEnabled(CHILD);
    }

    @Test
    void enabledStateIsReadAgainOnEveryList() throws Exception {
        when(states.isEnabled(FIRST)).thenReturn(true, false);
        send(get(ModuleController.MODULES_PATH).param("limit", "1"), ADMIN_TOKEN).andExpect(jsonPath("$.items[0].enabled").value(true));
        send(get(ModuleController.MODULES_PATH).param("limit", "1"), ADMIN_TOKEN).andExpect(jsonPath("$.items[0].enabled").value(false));
        verify(states, times(2)).isEnabled(FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {UNKNOWN, "A-FIXTURE"})
    void unavailableOrDifferentlyCasedIdsDoNotConsultHistoricalState(String id) throws Exception {
        send(patch(ModuleController.MODULES_PATH + "/" + id).contentType(MediaType.APPLICATION_JSON).content(enabled(true)), ADMIN_TOKEN)
                .andExpect(status().isNotFound()).andExpect(content().json(error(ModuleController.NOT_FOUND), JsonCompareMode.STRICT));
        verifyNoInteractions(states);
    }

    @Test
    void onlyDomainDependencyConflictMapsToClosed409() throws Exception {
        doThrow(new ModuleDependencyConflictException(FIRST, CHILD)).when(states).disable(FIRST);
        send(patch(ModuleController.MODULES_PATH + "/" + FIRST).contentType(MediaType.APPLICATION_JSON).content(enabled(false)), ADMIN_TOKEN)
                .andExpect(status().isConflict()).andExpect(content().json(error(ModuleController.DEPENDENCY_CONFLICT), JsonCompareMode.STRICT));
    }

    @Test
    void unexpectedStateFailureIsNotMisreportedAsConflictOrUnavailable() {
        var failure = new IllegalStateException("unexpected fixture failure");
        doThrow(failure).when(states).disable(FIRST);
        assertThatThrownBy(() -> send(patch(ModuleController.MODULES_PATH + "/" + FIRST)
                .contentType(MediaType.APPLICATION_JSON).content(enabled(false)), ADMIN_TOKEN)).hasRootCause(failure);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + " " + bearer))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue()))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private String enabled(boolean value) { return json.writeValueAsString(Map.of(ModuleController.ENABLED_FIELD, value)); }
    private String error(String code) { return json.writeValueAsString(Map.of("code", code, "errors", java.util.List.of(Map.of("code", code)))); }
    private Map<String, Object> representation(String id, boolean enabled) {
        return Map.of("id", id, "moduleApiVersion", ModuleApi.CURRENT_VERSION, "dependencies",
                id.equals(CHILD) ? List.of(FIRST, SECOND) : List.of(), "enabled", enabled);
    }
    private String collection(List<Map<String, Object>> items, String offset, int limit) {
        return json.writeValueAsString(Map.of("items", items, "offset", new java.math.BigInteger(offset),
                "limit", limit, "total", 3, "order", List.of(Map.of("field", "id", "direction", "asc"))));
    }
    private ErbasModule module(String id, List<String> dependencies) {
        return new ErbasModule() {
            @Override public ModuleDescriptor descriptor() { return new ModuleDescriptor(id, ModuleApi.CURRENT_VERSION, dependencies); }
            @Override public void initialize() { }
        };
    }
}
