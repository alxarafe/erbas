package com.alxarafe.erbas.auth.http;

import java.util.Map;

import com.alxarafe.erbas.auth.application.LoginUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class LoginController {

    private final LoginUseCase login;
    // Local parser: do not alter the application's general JSON conversion settings.
    private final JsonMapper parser = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public LoginController(LoginUseCase login) {
        this.login = login;
    }

    @PostMapping(path = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> login(@RequestBody String body) {
        JsonNode request;
        try {
            request = parser.readTree(body);
        } catch (JacksonException malformed) {
            return invalidRequest();
        }
        if (request == null || !request.isObject() || request.size() != 2
                || !nonemptyString(request.get("email")) || !nonemptyString(request.get("password"))) {
            return invalidRequest();
        }
        var token = login.login(request.get("email").asString(), request.get("password").asString());
        if (token.isEmpty()) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .header("WWW-Authenticate", "Bearer").body(Map.of("code", "invalid_credentials"));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(Map.of("accessToken", token.orElseThrow()));
    }

    private static boolean nonemptyString(JsonNode value) {
        return value != null && value.isString() && !value.asString().isEmpty();
    }

    static ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "invalid_request"));
    }
}
