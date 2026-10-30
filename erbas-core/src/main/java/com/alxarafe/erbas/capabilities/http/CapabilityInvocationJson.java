package com.alxarafe.erbas.capabilities.http;

import com.alxarafe.erbas.capabilities.CapabilityInvocationHandler.InvalidInput;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict invocation boundary only; called after effective authorization. */
public final class CapabilityInvocationJson {
    public static final String INPUT = "input";
    private static final JsonMapper PARSER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(Integer.MAX_VALUE).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private CapabilityInvocationJson() { }
    public static JsonNode input(String body) {
        try {
            var root = PARSER.readTree(body);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has(INPUT)
                    || !root.get(INPUT).isObject()) throw new InvalidInput();
            return root.get(INPUT);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            throw new InvalidInput();
        }
    }
}
