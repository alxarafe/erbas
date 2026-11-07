package com.alxarafe.erbas.ai.infrastructure;

import java.util.List;
import com.alxarafe.erbas.ai.AiAssistantProvider;

/** Safe default when a provider is absent or configuration is invalid. */
public final class UnavailableAssistantProvider implements AiAssistantProvider {
    @Override public Turn respond(String message, List<Tool> tools, List<ToolResult> results) {
        throw new Unavailable();
    }
}
