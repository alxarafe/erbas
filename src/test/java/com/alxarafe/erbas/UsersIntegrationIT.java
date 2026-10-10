package com.alxarafe.erbas;

import java.util.Map;
import java.util.List;

import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class UsersIntegrationIT {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcAuthenticationStore store;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();
    private static final String EMAIL = "users-admin@example.test";
    private static final String PASSWORD = "foundation-admin-fixture";
    private String hash;
    private long adminId;
    private String adminToken;

    @BeforeAll
    void encodeFixture() { hash = encoder.encode(PASSWORD); }

    @BeforeEach
    void provisionAdmin() throws Exception {
        adminId = store.createUser(EMAIL, hash, true, true);
        adminToken = login(EMAIL, PASSWORD);
    }

    @Test
    void completeAdministrationFlowUsesRealPasswordsAndExistingBearers(CapturedOutput output) throws Exception {
        send(get("/api/auth/me"), adminToken).andExpect(status().isOk())
                .andExpect(content().json(json.writeValueAsString(Map.of("id", Long.toString(adminId),
                        "email", EMAIL, "enabled", true, "admin", true)), JsonCompareMode.STRICT));
        String email = " User-No-Normalization ";
        String password = "  密码😀abcdefghi  ";
        String id = create(email, password, false);
        String normalToken = login(email, password);
        send(get("/api/users"), normalToken).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(content().json("{\"code\":\"forbidden\"}", JsonCompareMode.STRICT));
        update(id, "{\"admin\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
        send(get("/api/users"), normalToken).andExpect(status().isOk());
        update(id, "{\"admin\":false}").andExpect(status().isOk());
        send(get("/api/users"), normalToken).andExpect(status().isForbidden());
        update(id, "{\"enabled\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        send(get("/api/auth/me"), normalToken).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().json("{\"code\":\"unauthorized\"}", JsonCompareMode.STRICT));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"invalid_credentials\"}", JsonCompareMode.STRICT));
        update(id, "{\"enabled\":true,\"admin\":false}").andExpect(status().isOk());
        String renewed = login(email, password);
        send(get("/api/auth/me"), renewed).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        String encoded = store.findUserByEmail(email).orElseThrow().passwordHash();
        assertThat(encoder.matches(password, encoded)).isTrue();
        for (String secret : new String[] {PASSWORD, hash, password, encoded, adminToken, normalToken, renewed}) {
            assertThat(output.getAll().contains(secret)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 256})
    void supplementaryPasswordsAreHashedAndAuthenticateUnchanged(int length) throws Exception {
        String password = "😀".repeat(length);
        String id = create("unicode@example.test", password, true);
        String token = login("unicode@example.test", password);
        send(get("/api/auth/me"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void sqlPaginationOrdersBeforeWindowingAndCountsDisabledUsers() throws Exception {
        long first = store.createUser("page-first@example.test", hash, true);
        long second = store.createUser("page-second@example.test", hash, false);
        long third = store.createUser("page-third@example.test", hash, true);
        // Move the oldest row's heap version after later inserts; SQL must impose its own order.
        jdbc.update("UPDATE auth_user SET email = email WHERE id = ?", adminId);
        var body = send(get("/api/users"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(0)).andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.order[0].field").value("id"))
                .andExpect(jsonPath("$.order[0].direction").value("asc"))
                .andReturn().getResponse().getContentAsString();
        var page = json.readTree(body);
        assertThat(page.size()).isEqualTo(5);
        var ids = new java.util.ArrayList<String>();
        page.get("items").forEach(user -> ids.add(user.get("id").asString()));
        assertThat(ids).containsExactlyElementsOf(List.of(adminId, first, second, third).stream()
                .map(Object::toString).toList());
        send(get("/api/users").param("limit", "1"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(Long.toString(adminId)))
                .andExpect(jsonPath("$.limit").value(1)).andExpect(jsonPath("$.total").value(4));
        send(get("/api/users").param("offset", "1").param("limit", "1"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(Long.toString(first)))
                .andExpect(jsonPath("$.offset").value(1)).andExpect(jsonPath("$.total").value(4));
        send(get("/api/users").param("offset", "100"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.offset").value(100)).andExpect(jsonPath("$.limit").value(50));
        assertThat(store.countUsers()).isEqualTo(4);
        assertThat(store.findUserById(second).orElseThrow().enabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset=-1", "offset=abc", "limit=0", "limit=101", "limit=abc"})
    void invalidPaginationReturnsContractErrorWithoutChangingUsers(String query) throws Exception {
        var parts = query.split("=", -1);
        send(get("/api/users").param(parts[0], parts[1]), adminToken).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
        assertThat(store.countUsers()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"admin\":false}", "{\"enabled\":false}"})
    void lastAdminHttpOutcomePreservesDatabaseState(String body) throws Exception {
        update(Long.toString(adminId), body).andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"last_admin\"}", JsonCompareMode.STRICT));
        assertThat(store.findUserById(adminId).orElseThrow().admin()).isTrue();
        assertThat(store.findUserById(adminId).orElseThrow().enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selfChangesSucceedWithAnotherAdminAndAffectNextRequest(boolean disable) throws Exception {
        store.createUser("other-admin@example.test", hash, true, true);
        update(Long.toString(adminId), disable ? "{\"enabled\":false}" : "{\"admin\":false}")
                .andExpect(status().isOk());
        send(get("/api/users"), adminToken).andExpect(status().is(disable ? 401 : 403))
                .andExpect(content().json(disable ? "{\"code\":\"unauthorized\"}" : "{\"code\":\"forbidden\"}",
                        JsonCompareMode.STRICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user WHERE enabled AND admin", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/me", "GET,/api/users", "GET,/api/users/unknown", "POST,/api/users", "PATCH,/api/users/unknown"})
    void disabledBearerCannotAccessAnyUserOperation(String method, String path) throws Exception {
        long id = store.createUser("disabled-after-issue@example.test", hash, true, true);
        String token = login("disabled-after-issue@example.test", PASSWORD);
        store.updateUserState(id, false, null);
        send(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"), token)
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().json("{\"code\":\"unauthorized\"}", JsonCompareMode.STRICT));
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    private String create(String email, String password, boolean admin) throws Exception {
        String body = send(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password, "admin", admin))), adminToken)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.password_hash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).size()).isEqualTo(4);
        return json.readTree(body).get("id").asString();
    }

    private ResultActions update(String id, String body) throws Exception {
        return send(patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content(body), adminToken);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
