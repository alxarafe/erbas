package com.alxarafe.erbas.ai.http;

import com.alxarafe.erbas.ai.AiText;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Invoked only after authentication, AI permission and module gating. */
public final class AssistantJson {
    public static final String MESSAGE = "message";
    private static final JsonMapper PARSER = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private AssistantJson() { }
    public static String message(String body) {
        try {
            var root = PARSER.readTree(body);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has(MESSAGE)
                    || !root.get(MESSAGE).isString()) throw new InvalidRequest();
            String message = root.get(MESSAGE).asString();
            if (!AiText.validMessage(message)) throw new InvalidRequest();
            return message;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            throw new InvalidRequest();
        }
    }
    public static final class InvalidRequest extends RuntimeException { }
}
