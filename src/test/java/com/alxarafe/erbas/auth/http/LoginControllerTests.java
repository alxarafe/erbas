package com.alxarafe.erbas.auth.http;

import java.util.Optional;

import com.alxarafe.erbas.auth.application.LoginUseCase;
import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoginController.class)
@Import({AuthenticationConfiguration.class, LoginExceptionHandler.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LoginControllerTests {

    @Autowired private MockMvc mvc;
    @MockitoBean private LoginUseCase login;
    @MockitoBean private JdbcAuthenticationStore store;

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"email\":\"a\"}", "{\"password\":\"b\"}",
            "{\"email\":null,\"password\":\"b\"}", "{\"email\":\"a\",\"password\":null}",
            "{\"email\":42,\"password\":\"b\"}", "{\"email\":\"a\",\"password\":42}",
            "{\"email\":true,\"password\":\"b\"}", "{\"email\":\"a\",\"password\":false}",
            "{\"email\":[],\"password\":\"b\"}", "{\"email\":\"a\",\"password\":{}}",
            "{\"email\":\"\",\"password\":\"b\"}", "{\"email\":\"a\",\"password\":\"\"}",
            "{\"email\":\"a\",\"password\":\"b\",\"extra\":true}",
            "{\"Email\":\"a\",\"Password\":\"b\"}", "{\"email\":\"a\",\"Password\":\"b\"}",
            "[]", "[{}]", "null", "true", "42", "\"text\"", "{", "", "   ",
            "{\"email\":\"a\",\"password\":\"b\"} {}",
            "{\"email\":\"a\",\"password\":\"b\",}", "{'email':'a','password':'b'}"
    })
    void rejectsInvalidStructuresLocallyWithClosedError(String body) throws Exception {
        mvc.perform(post("/api/auth/login").contentType("Application/JSON").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"invalid_request\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(login, store);
    }

    @Test
    void returnsOnlyAccessTokenWithExactCacheHeaderAndNoSession() throws Exception {
        when(login.login("user", "secret")).thenReturn(Optional.of("synthetic-token"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"accessToken\":\"synthetic-token\"}", JsonCompareMode.STRICT));
    }

    @Test
    void nonemptyStringsArePassedUnchangedWithNoEmailPolicy() throws Exception {
        when(login.login("not-an-email", " \t ")).thenReturn(Optional.empty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\" \\t \"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"invalid_credentials\"}", JsonCompareMode.STRICT));
        verify(login).login("not-an-email", " \t ");
    }

    @Test
    void missingBearerAndBasicCannotAccessOtherResourcesOrCreateSession() throws Exception {
        mvc.perform(get("/some-protected-resource"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/some-protected-resource").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }
}
