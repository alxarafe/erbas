package com.alxarafe.erbas.resources.http;

import java.util.*;
import com.alxarafe.erbas.adapter.http.ContractError;
import com.alxarafe.erbas.resources.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ResourceController.class)
public final class ResourceErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception exception, HttpServletRequest request) {
        return response(exception, request);
    }
    public static ResponseEntity<?> response(Exception exception, HttpServletRequest request) {
        var code = exception instanceof ResourceFailure failure ? failure.code() : ResourceFailure.Code.resource_execution_failed;
        HttpStatus status = switch (code) {
            case invalid_request -> HttpStatus.BAD_REQUEST;
            case unauthorized -> HttpStatus.UNAUTHORIZED;
            case forbidden -> HttpStatus.FORBIDDEN;
            case resource_not_found, resource_action_not_found -> HttpStatus.NOT_FOUND;
            case resource_operation_not_supported -> HttpStatus.METHOD_NOT_ALLOWED;
            case resource_conflict, resource_action_conflict, resource_action_unavailable -> HttpStatus.CONFLICT;
            case module_disabled, resource_execution_unavailable -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (status == HttpStatus.METHOD_NOT_ALLOWED && request.getAttribute(ResourceController.DEFINITION_ATTRIBUTE) instanceof ResourceDefinition definition) {
            var methods = new LinkedHashSet<HttpMethod>();
            boolean item = request.getRequestURI().substring(ResourceController.RECORDS.length() + 1).contains("/");
            if (definition.operations().containsKey(item ? ResourceOperation.get : ResourceOperation.list)) methods.add(HttpMethod.GET);
            if (!item && definition.operations().containsKey(ResourceOperation.create)) methods.add(HttpMethod.POST);
            if (item && definition.operations().containsKey(ResourceOperation.update)) methods.add(HttpMethod.PATCH);
            if (item && definition.operations().containsKey(ResourceOperation.delete)) methods.add(HttpMethod.DELETE);
            response.allow(methods.toArray(HttpMethod[]::new));
        }
        return response.body(ContractError.of(code.name()));
    }
}
