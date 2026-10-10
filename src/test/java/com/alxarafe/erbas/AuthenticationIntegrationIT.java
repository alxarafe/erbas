package com.alxarafe.erbas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.auth.application.UserAdministration.UserUpdateOutcome;
import com.alxarafe.erbas.auth.infrastructure.BearerAuthenticationProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "erbas.auth.access-token-ttl=PT2M")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(AuthenticationIntegrationIT.TestEndpoints.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIntegrationIT {

    private static final String EMAIL = "integration@example.test";
    private static final String PASSWORD = " \t" + "密".repeat(40) + "x".repeat(256) + "\n ";
    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private final JsonMapper json = new JsonMapper();
    private String encodedPassword;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAuthenticationStore store;
    @Autowired private PasswordEncoder encoder;
    @Autowired private TestClock clock;

    @BeforeAll
    void encodeTestPassword() {
        encodedPassword = encoder.encode(PASSWORD);
    }

    @BeforeEach
    void resetClock() {
        clock.now = START;
    }

    @Test
    void issuedTokenAuthenticatesStableIdentityWithoutCredentialsAuthoritiesOrSession(CapturedOutput output)
            throws Exception {
        long id = store.createUser(EMAIL, encodedPassword, true);
        assertThat(store.findUserByEmail(EMAIL).orElseThrow().passwordHash().equals(PASSWORD)).isFalse();
        assertThat(encoder.matches(PASSWORD, store.findUserByEmail(EMAIL).orElseThrow().passwordHash())).isTrue();
        String token = loginToken(EMAIL, PASSWORD);
        assertThat(token.matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(Base64.getUrlDecoder().decode(token).length).isEqualTo(32);
        String expectedDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        String storedDigest = jdbc.queryForObject("SELECT token_hash FROM auth_access_token WHERE user_id = ?",
                String.class, id);
        assertThat(storedDigest.equals(expectedDigest)).isTrue();
        assertThat(storedDigest.equals(token)).isFalse();
        var created = jdbc.queryForObject("SELECT created_at FROM auth_access_token WHERE user_id = ?",
                Timestamp.class, id).toInstant();
        var expires = jdbc.queryForObject("SELECT expires_at FROM auth_access_token WHERE user_id = ?",
                Timestamp.class, id).toInstant();
        assertThat(created).isEqualTo(START);
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(2));
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
                .andExpect(content().json("""
                        {"id":"%s","email":"%s","admin":false,"authenticated":true,"credentialsAbsent":true,"authorities":0}
                        """.formatted(id, EMAIL), JsonCompareMode.STRICT));
        mvc.perform(get("/__test/authenticated")).andExpect(status().isUnauthorized());
        String second = loginToken(EMAIL, PASSWORD);
        assertThat(second.equals(token)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_access_token WHERE user_id = ?",
                Integer.class, id)).isEqualTo(2);
        for (String sensitive : new String[] {PASSWORD, encodedPassword, token, second, expectedDigest,
                "Authorization:", "authorization:"}) {
            assertThat(output.getAll().contains(sensitive)).isFalse();
        }
    }

    @Test
    void invalidAndAbsentTokensNeverAuthenticate() throws Exception {
        for (String header : new String[] {"Bearer " + "a".repeat(43), "Bearer invalid", "Bearer a b",
                "Basic dXNlcjpwYXNz", "Bearer"}) {
            mvc.perform(get("/__test/authenticated").header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
        mvc.perform(get("/__test/authenticated")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiryIsDeterministicAtExactConfiguredDeadline() throws Exception {
        store.createUser(EMAIL, encodedPassword, true);
        String token = loginToken(EMAIL, PASSWORD);
        clock.now = START.plusSeconds(119);
        mvc.perform(get("/__test/authenticated").header("Authorization", "bearer " + token))
                .andExpect(status().isOk());
        clock.now = START.plusSeconds(120);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        clock.now = START.plusSeconds(121);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownWrongAndDisabledCredentialsHaveIdenticalClosedResponse() throws Exception {
        store.createUser(EMAIL, encodedPassword, true);
        store.createUser("disabled@example.test", encodedPassword, false);
        String wrong = failedLogin(EMAIL, PASSWORD + "different-after-byte-72");
        assertThat(failedLogin("absent@example.test", PASSWORD)).isEqualTo(wrong);
        assertThat(failedLogin("disabled@example.test", PASSWORD)).isEqualTo(wrong);
        assertThat(failedLogin(EMAIL, PASSWORD.trim())).isEqualTo(wrong);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_access_token", Integer.class)).isZero();
    }

    @Test
    void disablingUserInvalidatesPreviouslyIssuedTokenAndFutureLogin() throws Exception {
        long id = store.createUser(EMAIL, encodedPassword, true);
        String token = loginToken(EMAIL, PASSWORD);
        assertThat(store.updateUserState(id, false, null).outcome())
                .isEqualTo(UserUpdateOutcome.UPDATED);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        failedLogin(EMAIL, PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_access_token WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void adminIdentityAndAuthorityFollowDatabaseStateWithoutReissuingBearer() throws Exception {
        long id = store.createUser(EMAIL, encodedPassword, true, true);
        store.createUser("other-admin@example.test", encodedPassword, true, true);
        String token = loginToken(EMAIL, PASSWORD);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.email").value(EMAIL)).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.authorities").value(1));
        assertThat(store.updateUserState(id, null, false).outcome())
                .isEqualTo(UserUpdateOutcome.UPDATED);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.authorities").value(0));
        assertThat(store.updateUserState(id, null, true).outcome())
                .isEqualTo(UserUpdateOutcome.UPDATED);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.authorities").value(1));
        assertThat(store.updateUserState(id, false, null).outcome())
                .isEqualTo(UserUpdateOutcome.UPDATED);
        mvc.perform(get("/__test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAdministratorCanReadCurrentIdentityAndUsers() throws Exception {
        store.createUser(EMAIL, encodedPassword, true, true);
        String token = loginToken(EMAIL, PASSWORD);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value(EMAIL));
    }

    @Test
    void unusableStoredEncodingDoesNotRevealAccount() throws Exception {
        store.createUser(EMAIL, "unsupported-encoding", true);
        failedLogin(EMAIL, PASSWORD);
    }

    @Test
    void structuralFailureUsesContractErrorWithRealFilterChain() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("Application/JSON").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
    }

    private String loginToken(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
                .andReturn().getResponse().getContentAsString();
        var response = json.readTree(body);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.get("accessToken").isString()).isTrue();
        return response.get("accessToken").asString();
    }

    private String failedLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"invalid_credentials\"}", JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpoints {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean TestOnlyController authenticatedController() { return new TestOnlyController(); }
    }

    @RestController
    @TestComponent
    static class TestOnlyController {
        @GetMapping("/__test/authenticated")
        Map<String, Object> authenticated(Authentication authentication) {
            var identity = (UserIdentity) authentication.getPrincipal();
            assertThat(authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals(BearerAuthenticationProvider.ADMIN_AUTHORITY)))
                    .isEqualTo(identity.admin());
            return Map.of("id", authentication.getName(), "authenticated", authentication.isAuthenticated(),
                    "email", identity.email(), "admin", identity.admin(),
                    "credentialsAbsent", authentication.getCredentials() == null,
                    "authorities", authentication.getAuthorities().size());
        }
    }

    static class TestClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
