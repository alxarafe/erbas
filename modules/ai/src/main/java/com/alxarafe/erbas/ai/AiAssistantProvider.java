package com.alxarafe.erbas.ai;

import java.util.List;
import com.alxarafe.erbas.capabilities.Capability;
import tools.jackson.databind.JsonNode;

/** Provider-neutral synchronous turns. A model's tool requests carry no authority. */
@FunctionalInterface
public interface AiAssistantProvider {
    Turn respond(String message, List<Capability> tools, List<ToolResult> results);
    sealed interface Turn permits Answer, ToolCall { }
    record Answer(String text) implements Turn { }
    record ToolCall(String capabilityId, JsonNode input) implements Turn { }
    record ToolResult(String capabilityId, JsonNode output) { }
    final class Unavailable extends RuntimeException { }
}
