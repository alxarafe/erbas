package com.alxarafe.erbas.resources;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict parsing confined to the new protocol; legacy parsers remain unchanged. */
public final class ResourceJson {
    public static final int NUMERIC_BOUND = 4096;
    public static final int MAX_BODY_BYTES = 1_048_576;
    public static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(NUMERIC_BOUND)
                    .maxStringLength(MAX_BODY_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private ResourceJson() { }
    public static JsonNode object(String body) {
        try {
            if (body == null || body.length() > MAX_BODY_BYTES) throw ResourceFailure.invalid();
            var node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw ResourceFailure.invalid();
            return node;
        } catch (RuntimeException invalid) { throw ResourceFailure.invalid(); }
    }
    public static boolean validText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c) && (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))) return false;
        }
        return true;
    }
}
