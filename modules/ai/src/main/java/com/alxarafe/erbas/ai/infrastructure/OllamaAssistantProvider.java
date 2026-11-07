package com.alxarafe.erbas.ai.infrastructure;

import com.alxarafe.erbas.ai.AiAssistantProvider;
import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;

/** Native Ollama protocol; legacy explicit endpoint configuration remains supported. */
public final class OllamaAssistantProvider extends HttpAssistantProvider {
    public static final String ENDPOINT_PROPERTY = "erbas.ai.ollama.endpoint";
    public static final String MODEL_PROPERTY = "erbas.ai.ollama.model";
    public static final String TIMEOUT_SECONDS_PROPERTY = "erbas.ai.ollama.timeout-seconds";
    public static final String CHAT_PATH = "/api/chat";

    public static AiAssistantProvider configured(Environment environment) {
        try {
            return new OllamaAssistantProvider(URI.create(environment.getProperty(ENDPOINT_PROPERTY, "").strip()),
                    environment.getProperty(MODEL_PROPERTY, "").strip(),
                    Duration.ofSeconds(Long.parseLong(environment.getProperty(TIMEOUT_SECONDS_PROPERTY,
                            Long.toString(DEFAULT_REQUEST_TIMEOUT.toSeconds())).strip())));
        } catch (IllegalArgumentException invalid) { return new UnavailableAssistantProvider(); }
    }
    public OllamaAssistantProvider(URI endpoint, String model) {
        this(endpoint, model, DEFAULT_REQUEST_TIMEOUT);
    }
    public OllamaAssistantProvider(URI endpoint, String model, Duration timeout) {
        this(endpoint, model, timeout, "", true);
    }
    public OllamaAssistantProvider(URI endpoint, String model, Duration timeout, String credential, boolean allowHttp) {
        super(endpoint, model, timeout, credential, allowHttp, false);
        if (!CHAT_PATH.equals(endpoint.getPath())) throw new IllegalArgumentException("Invalid native chat endpoint");
    }
}
