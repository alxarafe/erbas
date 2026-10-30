package com.alxarafe.erbas.ai;

import java.util.Map;
import com.alxarafe.erbas.ai.http.AssistantJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AssistantJsonTests {
    private final JsonMapper json = new JsonMapper();
    @Test void countsUnicodeScalarsInsteadOfUtf16CodeUnits() {
        String ascii = "a".repeat(AiText.MESSAGE_MAXIMUM_SCALARS);
        String supplementary = "\ud83d\ude00".repeat(AiText.MESSAGE_MAXIMUM_SCALARS);
        for (String value : new String[] {ascii, supplementary, "a" + supplementary.substring(2)}) {
            assertThat(AssistantJson.message(body(value))).isEqualTo(value);
        }
        for (String value : new String[] {ascii + "a", supplementary + "a"}) {
            assertThatThrownBy(() -> AssistantJson.message(body(value))).isInstanceOf(AssistantJson.InvalidRequest.class);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\r\n", "\u00a0\u2003\ufeff", "\0", "a\0b", "\ud800", "\udfff", "a\ud800b", "a\udfffb"})
    void rejectsEmptyWhitespaceNulAndUnpairedSurrogates(String message) {
        assertThatThrownBy(() -> AssistantJson.message(body(message))).isInstanceOf(AssistantJson.InvalidRequest.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[]", "true", "1", "\"message\"", "{\"message\":null}",
            "{\"message\":1}", "{\"message\":[]}", "{\"message\":{}}", "{\"message\":true}",
            "{\"message\":\"hello\",\"unknown\":true}", "{\"message\":\"hello\",\"message\":\"hello\"}",
            "{\"message\":\"hello\"} {}"})
    void rejectsMalformedAndNonClosedBodies(String body) {
        assertThatThrownBy(() -> AssistantJson.message(body)).isInstanceOf(AssistantJson.InvalidRequest.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"history", "conversationId", "provider", "model", "temperature", "reasoning", "prompt", "tools",
            "invocationId", "user", "roles", "permissions", "module", "executionContext"})
    void rejectsCallerExecutionAndSecurityFields(String field) {
        String body = json.writeValueAsString(Map.of(AssistantJson.MESSAGE, "hello", field, "spoofed"));
        assertThatThrownBy(() -> AssistantJson.message(body)).isInstanceOf(AssistantJson.InvalidRequest.class);
    }
    @Test void acceptsJsonSchemaNonWhitespaceAndRejectsInvalidFinalAnswers() {
        assertThat(AssistantJson.message(body("\u0085"))).isEqualTo("\u0085");
        assertThat(AiText.validAnswer(" ")).isFalse();
        assertThat(AiText.validAnswer("\ud800")).isFalse();
        assertThat(AiText.validAnswer("\0")).isFalse();
        assertThat(AiText.validAnswer(null)).isFalse();
    }
    private String body(String message) { return json.writeValueAsString(Map.of(AssistantJson.MESSAGE, message)); }
}
