package com.alxarafe.erbas.mcp;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpError;
import static com.alxarafe.erbas.mcp.CoreHttp.*;

/** Narrow wire adapter; CORE remains the sole input, permission and execution authority. */
final class UomTool {
    static final String LIST_REFERENCE = "#/components/schemas/CapabilityListInput";
    static final int CORE_MAXIMUM_LIMIT = 100;
    static final int CORE_DEFAULT_LIMIT = 50;
    static final String INVALID_CORE_SCHEMA = "unsupported_core_schema";
    static final String OFFSET = "offset";
    static final String LIMIT = "limit";
    static final String INVOCATION_ID = "invocationId";
    static final String CAPABILITY_ID = "capabilityId";
    static final String OUTPUT = "output";
    static final Set<String> SAFE_CORE_CODES = Set.of("unauthorized", "capability_not_found", "invalid_request",
            "capability_execution_failed");
    private final CoreHttp core;

    UomTool(CoreHttp core) { this.core = core; }

    McpSchema.ListToolsResult list() {
        var response = core.request(CAPABILITY_PATH, null);
        if (response.status() == HttpURLConnection.HTTP_NOT_FOUND) return McpSchema.ListToolsResult.builder(List.of()).build();
        if (response.status() != HttpURLConnection.HTTP_OK) throw safeError();
        var descriptor = response.body();
        if (!TOOL.equals(descriptor.path("id").asString()) || !"read".equals(descriptor.path("effect").asString())
                || !LIST_REFERENCE.equals(descriptor.path("inputSchema").path("$ref").asString())
                || descriptor.path("inputSchema").size() != 1) throw new IllegalStateException(INVALID_CORE_SCHEMA);
        // Explicit resolution of the only published schema this spike supports; no remote refs.
        var schema = Map.<String, Object>of("type", "object", "additionalProperties", false, "properties", Map.of(
                OFFSET, Map.of("type", "integer", "minimum", 0, "default", 0),
                LIMIT, Map.of("type", "integer", "minimum", 1, "maximum", CORE_MAXIMUM_LIMIT, "default", CORE_DEFAULT_LIMIT)));
        var tool = McpSchema.Tool.builder(TOOL, schema).description(descriptor.path("description").asString()).build();
        return McpSchema.ListToolsResult.builder(List.of(tool)).build();
    }

    McpSchema.CallToolResult call(McpSchema.CallToolRequest request) {
        if (!TOOL.equals(request.name())) throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                .message("unknown_tool").build();
        var response = core.request(INVOCATION_PATH, Map.of(INPUT,
                request.arguments() == null ? Map.of() : request.arguments()));
        if (response.status() != HttpURLConnection.HTTP_OK) {
            String code = response.body().path(CODE).asString();
            return result(Map.of(CODE, SAFE_CORE_CODES.contains(code == null ? "" : code) ? code : UNAVAILABLE,
                    HTTP_STATUS, response.status()), true);
        }
        var body = response.body();
        if (!TOOL.equals(body.path(CAPABILITY_ID).asString()) || !body.path(INVOCATION_ID).isString()
                || body.path(INVOCATION_ID).asString().isBlank() || !body.path(OUTPUT).isObject()
                || body.size() != 3) throw safeError();
        return result(JSON.convertValue(body, Map.class), false);
    }

    private McpSchema.CallToolResult result(Map<?, ?> body, boolean error) {
        return McpSchema.CallToolResult.builder().isError(error).structuredContent(body)
                .content(List.of(McpSchema.TextContent.builder(JSON.writeValueAsString(body)).build())).build();
    }

    static McpError safeError() {
        return McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(UNAVAILABLE).build();
    }
}
