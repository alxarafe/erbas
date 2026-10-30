package com.alxarafe.erbas.capabilities;

import java.util.Set;
import java.math.BigDecimal;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import tools.jackson.databind.JsonNode;

/** Published structured list pagination, reusing COLLECTIONS-001 bounds and defaults. */
public final class CapabilityListInput {
    public static final String OFFSET = "offset";
    public static final String LIMIT = "limit";
    private static final Set<String> FIELDS = Set.of(OFFSET, LIMIT);
    private CapabilityListInput() { }
    public static PagedCollection.Pagination parse(JsonNode input) {
        if (input == null || !input.isObject() || input.properties().stream().anyMatch(entry ->
                !FIELDS.contains(entry.getKey()) || !entry.getValue().isNumber())) {
            throw new CapabilityInvocationHandler.InvalidInput();
        }
        try {
            return PagedCollection.pagination(integer(input, OFFSET), integer(input, LIMIT))
                    .orElseThrow(CapabilityInvocationHandler.InvalidInput::new);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new CapabilityInvocationHandler.InvalidInput();
        }
    }
    private static String integer(JsonNode input, String field) {
        return input.has(field) ? new BigDecimal(input.get(field).asString()).toBigIntegerExact().toString() : null;
    }
}
