package com.alxarafe.erbas.resources;

import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration.definition;

class ResourceQueryTests {
    private ResourceQuery query(Map<String, String[]> parameters) { return ResourceQuery.validate(definition(), ResourceQuery.transport(parameters)); }
    @ParameterizedTest @ValueSource(strings = {"offset=-1", "offset=+1", "offset=1.0", "limit=0", "limit=101", "extra=1", "search= ", "search=\u00a0", "sort=__unknown"})
    void rejectsUnknownMalformedOrIneligibleQuery(String parameter) {
        var parts = parameter.split("=", -1);
        assertThatThrownBy(() -> query(Map.of(parts[0], new String[] {parts[1]}))).isInstanceOf(ResourceFailure.class);
    }
    @Test void singularAndRepeatedGrammarIsStrict() {
        assertThatThrownBy(() -> query(Map.of("limit", new String[] {"1", "2"}))).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> query(Map.of("sort", new String[] {"name", "-name"}))).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> query(Map.of("search", new String[] {"a".repeat(ResourceQuery.MAX_SEARCH_LENGTH + 1)}))).isInstanceOf(ResourceFailure.class);
        assertThat(query(Map.of()).limit()).isEqualTo(ResourceQuery.DEFAULT_LIMIT);
        assertThat(query(Map.of("offset", new String[] {"999999999999999999999999999999"})).offset())
                .isEqualTo(new BigInteger("999999999999999999999999999999"));
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"field\":\"name\",\"operator\":\"eq\",\"value\":1}",
            "{\"field\":\"name\",\"operator\":\"eq\",\"value\":\"a\",\"extra\":true}",
            "{\"field\":\"name\",\"field\":\"code\",\"operator\":\"eq\",\"value\":\"a\"}",
            "{\"field\":\"missing\",\"operator\":\"eq\",\"value\":\"a\"}",
            "{\"field\":\"active\",\"operator\":\"contains\",\"value\":true}",
            "{\"field\":\"name\",\"operator\":\"OR\",\"value\":\"a\"}",
            "{\"field\":\"note\",\"operator\":\"lt\",\"value\":null}"})
    void invalidClosedTypedFiltersAreRejected(String filter) { assertThatThrownBy(() -> query(Map.of("filter", new String[] {filter}))).isInstanceOf(ResourceFailure.class); }
    @Test void literalSearchAndFiltersCombineWithAndAndRespectNullableSemantics() {
        var absent = new HashMap<String, Object>(); absent.put("name", "First literal space"); absent.put("note", null);
        var present = new HashMap<>(absent); present.put("note", "value");
        assertThat(query(Map.of("search", new String[] {"literal space"}, "filter", new String[] {filter("note", "eq", "null")})).matches(absent)).isTrue();
        assertThat(query(Map.of("search", new String[] {"Literal space"})).matches(absent)).isFalse();
        assertThat(query(Map.of("filter", new String[] {filter("note", "ne", "\"value\"")})).matches(absent)).isTrue();
        assertThat(query(Map.of("filter", new String[] {filter("note", "ne", "null")})).matches(present)).isTrue();
        assertThat(query(Map.of("filter", new String[] {filter("note", "contains", "\"val\"")})).matches(absent)).isFalse();
        assertThat(query(Map.of("filter", new String[] {filter("note", "contains", "\"val\""), filter("name", "startsWith", "\"No\"")})).matches(present)).isFalse();
    }
    @Test void repeatedSortPriorityAndNullOrderingAreDeterministic() {
        var a = new HashMap<String, Object>(Map.of("id", UUID.randomUUID(), "name", "same")); a.put("note", null);
        var b = new HashMap<String, Object>(Map.of("id", UUID.randomUUID(), "name", "same", "note", "value"));
        assertThat(query(Map.of("sort", new String[] {"name", "note"})).comparator().compare(a, b)).isPositive();
        assertThat(query(Map.of("sort", new String[] {"name", "-note"})).comparator().compare(a, b)).isNegative();
    }
    private String filter(String field, String operator, String value) {
        return "{\"field\":\"" + field + "\",\"operator\":\"" + operator + "\",\"value\":" + value + "}";
    }
}
