package com.alxarafe.erbas.ai;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Provider-neutral synchronous turns. A model's tool requests carry no authority. */
@FunctionalInterface
public interface AiAssistantProvider {
    String INVALID_ARGUMENTS = "invalid_arguments";
    Turn respond(String message, List<Tool> tools, List<ToolResult> results);
    enum Kind { CAPABILITY, PRIMITIVE }
    /** Provider view: no permissions, module state, executor or transport address. */
    record Tool(Kind kind, String id, String name, String description, JsonNode inputSchema) {
        public Tool { inputSchema = inputSchema.deepCopy(); }
        @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
    }
    sealed interface Turn permits Answer, ToolCall, PrimitiveCall { }
    record Answer(String text) implements Turn { }
    record ToolCall(String capabilityId, JsonNode input, String callId, String content) implements Turn {
        public ToolCall(String capabilityId, JsonNode input) { this(capabilityId, input, UUID.randomUUID().toString(), ""); }
    }
    record PrimitiveCall(String primitiveId, JsonNode input, String callId, String content) implements Turn {
        public PrimitiveCall(String primitiveId, JsonNode input) { this(primitiveId, input, UUID.randomUUID().toString(), ""); }
    }
    /** Request-local transcript entry, including rejected attempts; not public provenance. */
    record ToolResult(Kind kind, String id, JsonNode output, JsonNode input, String callId, String content) {
        public ToolResult(Kind kind, String id, JsonNode output) {
            this(kind, id, output, new tools.jackson.databind.json.JsonMapper().createObjectNode(), UUID.randomUUID().toString(), "");
        }
        public ToolResult { output = output.deepCopy(); input = input.deepCopy(); }
        @Override public JsonNode output() { return output.deepCopy(); }
        @Override public JsonNode input() { return input.deepCopy(); }
    }
    final class Unavailable extends RuntimeException { }
}
