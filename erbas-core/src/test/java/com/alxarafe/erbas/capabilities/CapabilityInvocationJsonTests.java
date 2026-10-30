package com.alxarafe.erbas.capabilities;

import java.math.BigInteger;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.capabilities.http.CapabilityInvocationJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CapabilityInvocationJsonTests {
    @Test void acceptsDefaultsAndStructuredIntegerPagination() {
        assertThat(parse("{\"input\":{}}"))
                .isEqualTo(new PagedCollection.Pagination(BigInteger.ZERO, PagedCollection.DEFAULT_LIMIT));
        assertThat(parse("{\"input\":{\"offset\":1,\"limit\":1}}"))
                .isEqualTo(new PagedCollection.Pagination(BigInteger.ONE, 1));
        assertThat(parse("{\"input\":{\"offset\":1e1,\"limit\":1.0}}"))
                .isEqualTo(new PagedCollection.Pagination(BigInteger.TEN, 1));
        assertThat(parse("{\"input\":{\"offset\":99999999999999999999999999999999999999999}}" ).offset())
                .isEqualTo(new BigInteger("99999999999999999999999999999999999999999"));
        String unboundedOffset = "9".repeat(1200);
        assertThat(parse("{\"input\":{\"offset\":" + unboundedOffset + "}}").offset())
                .isEqualTo(new BigInteger(unboundedOffset));
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[]", "1", "true", "\"text\"", "{\"input\":null}",
            "{\"input\":[]}", "{\"input\":\"text\"}", "{\"input\":1}", "{\"input\":{},\"caller\":\"user\"}",
            "{\"input\":{\"unknown\":1}}", "{\"input\":{\"permissions\":[]}}", "{\"input\":{},\"input\":{}}",
            "{\"input\":{\"offset\":0,\"offset\":1}}", "{\"input\":{}} {}", "{\"input\":{\"offset\":null}}",
            "{\"input\":{\"offset\":-1}}", "{\"input\":{\"limit\":0}}", "{\"input\":{\"limit\":101}}",
            "{\"input\":{\"offset\":\"1\"}}", "{\"input\":{\"limit\":true}}", "{\"input\":{\"offset\":1.1}}",
            "{\"input\":{\"limit\":1.5}}"})
    void rejectsInvalidClosedRequests(String body) {
        assertThatThrownBy(() -> parse(body)).isInstanceOf(CapabilityInvocationHandler.InvalidInput.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"user", "subject", "roles", "permissions", "scopes", "module", "caller", "executionContext", "invocationId"})
    void rejectsCallerSecurityFieldsAtBothLevels(String field) {
        assertThatThrownBy(() -> parse("{\"input\":{},\"" + field + "\":{}}"))
                .isInstanceOf(CapabilityInvocationHandler.InvalidInput.class);
        assertThatThrownBy(() -> parse("{\"input\":{\"" + field + "\":{}}}"))
                .isInstanceOf(CapabilityInvocationHandler.InvalidInput.class);
    }
    private PagedCollection.Pagination parse(String body) {
        return CapabilityListInput.parse(CapabilityInvocationJson.input(body));
    }
}
