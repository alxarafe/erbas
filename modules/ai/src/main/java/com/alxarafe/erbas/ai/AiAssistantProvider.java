package com.alxarafe.erbas.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Provider-neutral synchronous turns. A model's tool requests carry no authority. */
@FunctionalInterface
public interface AiAssistantProvider {
    Turn respond(String message, List<Tool> tools, List<ToolResult> results);
    enum Kind { CAPABILITY, PRIMITIVE }
    /** Provider view: no permissions, module state, executor or transport address. */
    record Tool(Kind kind, String id, String name, String description, JsonNode inputSchema) {
        public Tool { inputSchema = inputSchema.deepCopy(); }
        @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
    }
    sealed interface Turn permits Answer, ToolCall, PrimitiveCall { }
    record Answer(String text) implements Turn { }
    record ToolCall(String capabilityId, JsonNode input) implements Turn { }
    record PrimitiveCall(String primitiveId, JsonNode input) implements Turn { }
    record ToolResult(Kind kind, String id, JsonNode output) { }
    final class Unavailable extends RuntimeException { }
}
