package com.alxarafe.erbas.auth.http;

import java.util.Map;

import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.auth.application.UserIdentity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class UserController {
    private final UserAdministration users;
    // Tree inspection enforces exact scalar types without Jackson coercions.
    private final JsonMapper parser = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public UserController(UserAdministration users) { this.users = users; }

    public record User(String id, String email, boolean enabled, boolean admin) {
        static User from(UserIdentity identity) {
            return new User(Long.toString(identity.userId()), identity.email(), identity.enabled(), identity.admin());
        }
    }

    @GetMapping(path = "/api/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> me(@AuthenticationPrincipal UserIdentity identity) {
        return json(200, User.from(identity));
    }

    @GetMapping(path = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list() {
        return json(200, users.list().stream().map(User::from).toList());
    }

    @GetMapping(path = "/api/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        return users.find(id).map(user -> json(200, User.from(user)))
                .orElseGet(() -> error(404, "user_not_found"));
    }

    @PostMapping(path = "/api/users", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody String body) {
        var request = parse(body);
        if (request == null || !request.isObject() || request.size() != 3
                || !nonemptyString(request.get("email")) || !nonemptyString(request.get("password"))
                || !isBoolean(request.get("admin"))) {
            return error(400, "invalid_request");
        }
        var result = users.create(request.get("email").asString(), request.get("password").asString(),
                request.get("admin").asBoolean());
        return switch (result.outcome()) {
            case CREATED -> json(201, User.from(result.user()));
            case INVALID_REQUEST -> error(400, "invalid_request");
            case EMAIL_CONFLICT -> error(409, "email_conflict");
        };
    }

    @PatchMapping(path = "/api/users/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody String body) {
        var request = parse(body);
        if (request == null || !request.isObject() || request.isEmpty() || request.size() > 2
                || (request.has("enabled") && !isBoolean(request.get("enabled")))
                || (request.has("admin") && !isBoolean(request.get("admin")))
                || request.size() != (request.has("enabled") ? 1 : 0) + (request.has("admin") ? 1 : 0)) {
            return error(400, "invalid_request");
        }
        var result = users.update(id, request.has("enabled") ? request.get("enabled").asBoolean() : null,
                request.has("admin") ? request.get("admin").asBoolean() : null);
        return switch (result.outcome()) {
            case UPDATED -> json(200, User.from(result.user()));
            case NOT_FOUND -> error(404, "user_not_found");
            case LAST_ADMIN -> error(409, "last_admin");
        };
    }

    private JsonNode parse(String body) {
        try { return parser.readTree(body); }
        catch (JacksonException malformed) { return null; }
    }

    private static boolean nonemptyString(JsonNode value) {
        return value != null && value.isString() && !value.asString().isEmpty();
    }

    private static boolean isBoolean(JsonNode value) { return value != null && value.isBoolean(); }

    static ResponseEntity<Object> error(int status, String code) { return json(status, Map.of("code", code)); }

    private static ResponseEntity<Object> json(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(body);
    }
}
