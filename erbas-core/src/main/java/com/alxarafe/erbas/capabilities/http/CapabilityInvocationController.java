package com.alxarafe.erbas.capabilities.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import com.alxarafe.erbas.adapter.http.ContractError;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.CapabilityInvocation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CapabilityInvocationController {
    public static final String SUFFIX = "/invocations";
    public static final String PATH = CapabilityController.PATH + "/{capabilityId}" + SUFFIX;
    private final CapabilityInvocation invocation;
    public CapabilityInvocationController(CapabilityInvocation invocation) { this.invocation = invocation; }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> invoke(@AuthenticationPrincipal UserIdentity user, @PathVariable String capabilityId,
            HttpServletRequest request) {
        var result = invocation.invoke(user, capabilityId, () -> {
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                throw new com.alxarafe.erbas.capabilities.CapabilityInvocationHandler.InvalidInput();
            }
            try { return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        var status = result.errorCode() == null ? HttpStatus.OK : switch (result.errorCode()) {
            case CapabilityInvocation.INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case CapabilityInvocation.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CapabilityInvocation.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(result.errorCode() == null ? result.envelope() : ContractError.of(result.errorCode()));
    }
}
