package com.alxarafe.erbas.ai.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import com.alxarafe.erbas.adapter.http.ContractError;
import com.alxarafe.erbas.ai.AssistantInvocation;
import com.alxarafe.erbas.auth.application.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AssistantController {
    public static final String PATH = "/api/ai/assistant/invocations";
    private final AssistantInvocation assistant;
    public AssistantController(AssistantInvocation assistant) { this.assistant = assistant; }
    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> invoke(@AuthenticationPrincipal UserIdentity user, HttpServletRequest request) {
        var result = assistant.invoke(user, () -> {
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) throw new AssistantJson.InvalidRequest();
            try { return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        var status = result.errorCode() == null ? HttpStatus.OK : switch (result.errorCode()) {
            case AssistantInvocation.INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case AssistantInvocation.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AssistantInvocation.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case AssistantInvocation.UNAVAILABLE, AssistantInvocation.MODULE_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(result.errorCode() == null ? result.envelope() : ContractError.of(result.errorCode()));
    }
}
