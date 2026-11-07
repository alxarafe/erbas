package com.alxarafe.erbas.ai.infrastructure;

import java.net.URI;
import java.time.Duration;

/** Text-only Chat Completions protocol at an exact administratively configured operation URL. */
public final class OpenAiCompatibleAssistantProvider extends HttpAssistantProvider {
    public OpenAiCompatibleAssistantProvider(URI endpoint, String model, Duration timeout, String credential, boolean allowHttp) {
        super(endpoint, model, timeout, credential, allowHttp, true);
        if (credential.isEmpty() && !allowHttp) throw new IllegalArgumentException("Explicit local trust required for no-auth");
    }
}
