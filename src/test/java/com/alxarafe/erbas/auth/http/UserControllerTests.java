package com.alxarafe.erbas.auth.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alxarafe.erbas.auth.application.UserAdministration.UserUpdateOutcome;
import com.alxarafe.erbas.auth.application.UserAdministration.UserUpdateResult;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.auth.infrastructure.OpaqueAccessTokens;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({AuthenticationConfiguration.class, UserExceptionHandler.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class UserControllerTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private JdbcAuthenticationStore store;
    @MockitoBean private OpaqueAccessTokens tokens;
    @MockitoBean private PasswordEncoder encoder;
    private final JsonMapper json = new JsonMapper();
    private static final UserIdentity ADMIN = new UserIdentity(1, "admin@example.test", true, true);
    private static final UserIdentity NORMAL = new UserIdentity(2, "user@example.test", true, false);
    private static final String USER = "{\"id\":\"2\",\"email\":\"user@example.test\",\"enabled\":true,\"admin\":false}";

    @BeforeEach
    void bearerIdentities() {
        clearInvocations(store, encoder);
        when(tokens.findIdentity("admin-fixture")).thenReturn(Optional.of(ADMIN));
        when(tokens.findIdentity("normal-fixture")).thenReturn(Optional.of(NORMAL));
        when(store.updateUserState(anyLong(), any(), any()))
                .thenReturn(new UserUpdateResult(UserUpdateOutcome.NOT_FOUND, null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentIdentityUsesPrincipalWithoutAnotherLookup(boolean admin) throws Exception {
        send(get("/api/auth/me"), admin).andExpect(status().isOk())
                .andExpect(content().json(admin
                        ? "{\"id\":\"1\",\"email\":\"admin@example.test\",\"enabled\":true,\"admin\":true}"
                        : USER, JsonCompareMode.STRICT));
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/me", "GET,/api/users", "GET,/api/users/2", "POST,/api/users", "PATCH,/api/users/2"})
    void missingAndInvalidBearerUseClosed401(String method, String path) throws Exception {
        for (String bearer : new String[] {"", "Bearer invalid-fixture"}) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                            .header("Authorization", bearer))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("{\"code\":\"unauthorized\"}", JsonCompareMode.STRICT));
        }
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/users", "GET,/api/users/2", "GET,/api/users/unknown", "POST,/api/users", "PATCH,/api/users/unknown"})
    void authorizationPrecedesLookupAndValidation(String method, String path) throws Exception {
        send(request(org.springframework.http.HttpMethod.valueOf(method), path), false)
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(content().json("{\"code\":\"forbidden\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(store, encoder);
    }

    @Test
    void listAndGetExposeOnlyPublicIdentity() throws Exception {
        when(store.countUsers()).thenReturn(1L);
        when(store.listUsers(0, 50)).thenReturn(List.of(NORMAL));
        when(store.findUserById(2)).thenReturn(Optional.of(NORMAL));
        send(get("/api/users"), true).andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[" + USER + "],\"offset\":0,\"limit\":50,\"total\":1,"
                        + "\"order\":[{\"field\":\"id\",\"direction\":\"asc\"}]}", JsonCompareMode.STRICT));
        send(get("/api/users/2"), true).andExpect(status().isOk())
                .andExpect(content().json(USER, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "0", "-1", "9223372036854775808", "999"})
    void unusableAndUnknownIdsAreNotFound(String id) throws Exception {
        send(get("/api/users/" + id), true).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"user_not_found\"}", JsonCompareMode.STRICT));
        send(patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"admin\":false}"), true)
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"user_not_found\"}", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,1", "0,100"})
    void paginationPassesTheRequestedWindowAndPreservesTotal(long offset, int limit) throws Exception {
        when(store.countUsers()).thenReturn(3L);
        when(store.listUsers(offset, limit)).thenReturn(List.of(NORMAL));
        send(get("/api/users").param("offset", Long.toString(offset)).param("limit", Integer.toString(limit)), true)
                .andExpect(status().isOk()).andExpect(content().json("{\"items\":[" + USER
                        + "],\"offset\":" + offset + ",\"limit\":" + limit + ",\"total\":3,"
                        + "\"order\":[{\"field\":\"id\",\"direction\":\"asc\"}]}", JsonCompareMode.STRICT));
        verify(store).listUsers(offset, limit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "100", "9223372036854775808"})
    void offsetAtOrBeyondTotalReturnsAnEmptyWindowWithoutOverflow(String offset) throws Exception {
        when(store.countUsers()).thenReturn(3L);
        send(get("/api/users").param("offset", offset), true).andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[],\"offset\":" + offset + ",\"limit\":50,\"total\":3,"
                        + "\"order\":[{\"field\":\"id\",\"direction\":\"asc\"}]}", JsonCompareMode.STRICT));
        verify(store, never()).listUsers(anyLong(), anyInt());
    }

    @Test
    void emptyCollectionUsesTheSameClosedEnvelope() throws Exception {
        send(get("/api/users"), true).andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[],\"offset\":0,\"limit\":50,\"total\":0,"
                        + "\"order\":[{\"field\":\"id\",\"direction\":\"asc\"}]}", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset=-1", "offset=abc", "offset=1.5", "offset=", "limit=0", "limit=101",
            "limit=abc", "limit=1.5", "limit=", "limit=999999999999999999999999"})
    void invalidPaginationNeverReachesPersistence(String query) throws Exception {
        var parts = query.split("=", -1);
        send(get("/api/users").param(parts[0], parts[1]), true).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(store, encoder);
    }

    @Test
    void authorizationPrecedesInvalidPagination() throws Exception {
        send(get("/api/users").param("limit", "abc"), false).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").param("offset", "-1"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"code\":\"unauthorized\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(store, encoder);
    }

    @ParameterizedTest
    @CsvSource({"11,false", "12,true", "256,true", "257,false"})
    void passwordBoundariesCountSupplementaryCodePoints(int length, boolean accepted) throws Exception {
        String password = "😀".repeat(length);
        when(encoder.encode(password)).thenReturn("encoded-fixture");
        when(store.createUserIfEmailAvailable("user@example.test", "encoded-fixture", false))
                .thenReturn(Optional.of(NORMAL));
        var response = send(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", NORMAL.email(), "password", password, "admin", false))), true);
        if (accepted) {
            response.andExpect(status().isCreated()).andExpect(content().json(USER, JsonCompareMode.STRICT));
            verify(encoder).encode(password);
        } else {
            response.andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
            verifyNoInteractions(encoder, store);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void creationPreservesEmailAndPasswordAndUsesExistingEncoder(boolean admin) throws Exception {
        String email = " User ";
        String password = "  密码😀abcdefghi  ";
        var created = new UserIdentity(3, email, true, admin);
        when(encoder.encode(password)).thenReturn("encoded-fixture");
        when(store.createUserIfEmailAvailable(email, "encoded-fixture", admin)).thenReturn(Optional.of(created));
        send(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password, "admin", admin))), true)
                .andExpect(status().isCreated()).andExpect(content().json(json.writeValueAsString(
                        Map.of("id", "3", "email", email, "enabled", true, "admin", admin)), JsonCompareMode.STRICT));
        verify(encoder).encode(password);
    }

    @Test
    void duplicateEmailMapsWithoutDatabaseDetails() throws Exception {
        when(encoder.encode(anyString())).thenReturn("encoded-fixture");
        send(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user\",\"password\":\"abcdefghijkl\",\"admin\":false}"), true)
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"email_conflict\"}", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "{", "[]", "null", "true", "42", "{}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\"}",
            "{\"password\":\"abcdefghijkl\",\"admin\":false}", "{\"email\":\"x\",\"admin\":false}",
            "{\"email\":\"\",\"password\":\"abcdefghijkl\",\"admin\":false}",
            "{\"email\":42,\"password\":\"abcdefghijkl\",\"admin\":false}",
            "{\"email\":null,\"password\":\"abcdefghijkl\",\"admin\":false}",
            "{\"email\":\"x\",\"password\":42,\"admin\":false}",
            "{\"email\":\"x\",\"password\":null,\"admin\":false}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":\"false\"}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":0}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":null}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":false,\"enabled\":true}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":false,\"admin\":true}",
            "{\"email\":\"x\",\"password\":\"abcdefghijkl\",\"admin\":false} {}"
    })
    void invalidCreationNeverHashesOrPersists(String body) throws Exception {
        send(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body), true)
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(store, encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "[]", "null", "{}", "{\"email\":\"x\"}", "{\"id\":\"2\"}",
            "{\"password\":\"abcdefghijkl\"}", "{\"enabled\":null}", "{\"admin\":null}",
            "{\"enabled\":\"false\"}", "{\"admin\":1}", "{\"enabled\":false,\"extra\":true}",
            "{\"enabled\":true,\"enabled\":false}",
            "{\"enabled\":true} {}"})
    void invalidPatchNeverUpdates(String body) throws Exception {
        send(patch("/api/users/2").contentType(MediaType.APPLICATION_JSON).content(body), true)
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true}", "{\"admin\":false}", "{\"enabled\":true,\"admin\":false}"})
    void patchMapsUpdatedIdentityAndOnlySuppliedFields(String body) throws Exception {
        var node = json.readTree(body);
        Boolean enabled = node.has("enabled") ? true : null;
        Boolean admin = node.has("admin") ? false : null;
        when(store.updateUserState(2, enabled, admin)).thenReturn(new UserUpdateResult(UserUpdateOutcome.UPDATED, NORMAL));
        send(patch("/api/users/2").contentType(MediaType.APPLICATION_JSON).content(body), true)
                .andExpect(status().isOk()).andExpect(content().json(USER, JsonCompareMode.STRICT));
        verify(store).updateUserState(2, enabled, admin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":false}", "{\"admin\":false}"})
    void lastAdminOutcomeIsMappedWithoutAnotherCheck(String body) throws Exception {
        when(store.updateUserState(eq(1L), any(), any()))
                .thenReturn(new UserUpdateResult(UserUpdateOutcome.LAST_ADMIN, null));
        send(patch("/api/users/1").contentType(MediaType.APPLICATION_JSON).content(body), true)
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"last_admin\"}", JsonCompareMode.STRICT));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, boolean admin) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + (admin ? "admin-fixture" : "normal-fixture")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
