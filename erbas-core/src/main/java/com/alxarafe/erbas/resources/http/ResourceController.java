package com.alxarafe.erbas.resources.http;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.resources.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ResourceController {
    public static final String RECORDS = "/api/resources";
    public static final String ACTIONS = "/api/resource-actions";
    public static final String PRESENTATION_SUFFIX = "/presentation";
    public static final String PRESENTATION = ResourceMetadata.ROOT + "/{resource}" + PRESENTATION_SUFFIX;
    public static final String DEFINITION_ATTRIBUTE = ResourceController.class.getName() + ".definition";
    private final ResourceRegistry registry;
    private final ResourceRuntime runtime;
    private final ResourceMetadata metadata;
    private final ResourceActions actions;
    public ResourceController(ResourceRegistry registry, ResourceRuntime runtime, ResourceMetadata metadata, ResourceActions actions) {
        this.registry = registry; this.runtime = runtime; this.metadata = metadata; this.actions = actions;
    }
    @GetMapping(ResourceMetadata.ROOT)
    public ResponseEntity<?> discover(@AuthenticationPrincipal UserIdentity user, HttpServletRequest request) {
        runtime.current(user); noQuery(request); return cache(metadata.discover(user), null, request);
    }
    @GetMapping(ResourceMetadata.ROOT + "/{resource}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal UserIdentity user, @PathVariable String resource, HttpServletRequest request) {
        runtime.current(user); noQuery(request); var definition = registry.definition(resource);
        var projection = metadata.detail(user, definition);
        String etag = ResourceMetadata.hash(ResourceJson.JSON.writeValueAsString(projection));
        return cache(projection, etag, request);
    }
    @GetMapping(PRESENTATION)
    public ResponseEntity<?> presentation(@AuthenticationPrincipal UserIdentity user, @PathVariable String resource, HttpServletRequest request) {
        runtime.current(user); noQuery(request); var definition = registry.definition(resource);
        var projection = metadata.contextPresentation(definition);
        return cache(projection, ResourceMetadata.hash(ResourceJson.JSON.writeValueAsString(projection)), request);
    }
    @RequestMapping(path = {RECORDS + "/{resource}", RECORDS + "/{resource}/{id}"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<?> records(@AuthenticationPrincipal UserIdentity user, @PathVariable String resource,
            @PathVariable(required = false) String id, HttpServletRequest request) {
        runtime.current(user); var definition = registry.definition(resource); request.setAttribute(DEFINITION_ATTRIBUTE, definition);
        var method = HttpMethod.valueOf(request.getMethod());
        ResourceOperation operation;
        if (method == HttpMethod.GET) operation = id == null ? ResourceOperation.list : ResourceOperation.get;
        else if (method == HttpMethod.POST && id == null) operation = ResourceOperation.create;
        else if (method == HttpMethod.PATCH && id != null) operation = ResourceOperation.update;
        else if (method == HttpMethod.DELETE && id != null) operation = ResourceOperation.delete;
        else { noQuery(request); throw new ResourceFailure(ResourceFailure.Code.resource_operation_not_supported); }
        ResourceQuery.Transport query = null;
        if (operation == ResourceOperation.list) query = ResourceQuery.transport(request.getParameterMap()); else noQuery(request);
        Object identifier = id == null ? null : runtime.identifier(definition, id);
        var body = operation == ResourceOperation.create || operation == ResourceOperation.update ? ResourceRecords.transport(body(request)) : null;
        Object result = runtime.execute(user, definition, operation, identifier, body, query);
        var response = ResponseEntity.status(operation == ResourceOperation.create ? HttpStatus.CREATED
                : operation == ResourceOperation.delete ? HttpStatus.NO_CONTENT : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        if (operation == ResourceOperation.create) {
            var record = (tools.jackson.databind.JsonNode) result;
            response.header(HttpHeaders.LOCATION, RECORDS + "/" + ResourceMetadata.segment(resource) + "/"
                    + ResourceMetadata.segment(record.get(definition.idField()).asString()));
        }
        return response.body(result);
    }
    @PostMapping(ACTIONS + "/{resource}/{action}/execute")
    public ResponseEntity<?> execute(@AuthenticationPrincipal UserIdentity user, @PathVariable String resource,
            @PathVariable String action, HttpServletRequest request) {
        runtime.current(user); noQuery(request); var definition = registry.definition(resource);
        var declaration = actions.definition(definition, action);
        actions.execute(user, definition, declaration, ResourceJson.object(body(request)));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping(ACTIONS + "/{resource}/availability")
    public ResponseEntity<?> availability(@AuthenticationPrincipal UserIdentity user, @PathVariable String resource, HttpServletRequest request) {
        runtime.current(user); noQuery(request); var definition = registry.definition(resource);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(actions.availability(user, definition, ResourceJson.object(body(request))));
    }
    public static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ResourceFailure.invalid(); }
    public static String body(HttpServletRequest request) {
        try {
            var bytes = request.getInputStream().readNBytes(ResourceJson.MAX_BODY_BYTES + 1);
            if (bytes.length > ResourceJson.MAX_BODY_BYTES) throw ResourceFailure.invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.io.IOException invalid) { throw ResourceFailure.invalid(); }
    }
    public static ResponseEntity<?> cache(Object body, String version, HttpServletRequest request) {
        var response = ResponseEntity.status(version != null && matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), version)
                ? HttpStatus.NOT_MODIFIED : HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache").varyBy(HttpHeaders.AUTHORIZATION);
        if (version != null) response.eTag(version);
        return response.body(version != null && matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), version) ? null : body);
    }
    public static boolean matches(String header, String version) {
        if (header == null) return false;
        return Arrays.stream(header.split(",")).map(String::trim).anyMatch(tag -> tag.equals("*") || tag.equals("\"" + version + "\"") || tag.equals("W/\"" + version + "\""));
    }
}
