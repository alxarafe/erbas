package com.alxarafe.erbas.ai.infrastructure;

import java.util.List;
import com.alxarafe.erbas.ai.AiAssistantProvider;
import com.alxarafe.erbas.capabilities.Capability;

/** No real provider is installed in this milestone. */
public final class UnavailableAssistantProvider implements AiAssistantProvider {
    @Override public Turn respond(String message, List<Capability> tools, List<ToolResult> results) {
        throw new Unavailable();
    }
}
