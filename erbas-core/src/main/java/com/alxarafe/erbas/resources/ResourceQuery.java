package com.alxarafe.erbas.resources;

import java.math.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Typed AND grammar handed to the module adapter, never SQL or a transport address. */
public record ResourceQuery(BigInteger offset, int limit, String search, List<String> searchable,
        List<Sort> sort, List<Filter> filters) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_SEARCH_LENGTH = 512;
    private static final Set<String> PARAMETERS = Set.of("offset", "limit", "search", "sort", "filter");
    public record Sort(String field, boolean descending) { }
    public record Filter(String field, String operator, Object value) { }
    public record Transport(BigInteger offset, int limit, String search, List<String> sorts, List<JsonNode> filters) { }
    public static Transport transport(Map<String, String[]> query) {
        if (!PARAMETERS.containsAll(query.keySet())) throw ResourceFailure.invalid();
        var offset = unsigned(single(query, "offset", "0"));
        var rawLimit = unsigned(single(query, "limit", Integer.toString(DEFAULT_LIMIT)));
        if (rawLimit.signum() <= 0 || rawLimit.compareTo(BigInteger.valueOf(MAX_LIMIT)) > 0) throw ResourceFailure.invalid();
        String search = single(query, "search", null);
        if (search != null && (!ResourceJson.validText(search) || search.codePoints().allMatch(value -> Character.isWhitespace(value) || Character.isSpaceChar(value))
                || search.codePointCount(0, search.length()) > MAX_SEARCH_LENGTH)) throw ResourceFailure.invalid();
        var sorts = List.of(query.getOrDefault("sort", new String[0]));
        var names = sorts.stream().map(name -> name.startsWith("-") ? name.substring(1) : name).toList();
        if (names.stream().anyMatch(String::isEmpty) || names.stream().distinct().count() != names.size()) throw ResourceFailure.invalid();
        var filters = new ArrayList<JsonNode>();
        for (var raw : query.getOrDefault("filter", new String[0])) {
            var filter = ResourceJson.object(raw);
            if (filter.size() != 3 || !filter.has("field") || !filter.has("operator") || !filter.has("value")
                    || !filter.get("field").isString() || filter.get("field").asString().isEmpty()
                    || !filter.get("operator").isString() || filter.get("value").isContainer()) throw ResourceFailure.invalid();
            filters.add(filter);
        }
        return new Transport(offset, rawLimit.intValueExact(), search, sorts, List.copyOf(filters));
    }
    public static ResourceQuery validate(ResourceDefinition definition, Transport raw) {
        var searchable = definition.fields().stream().filter(field -> field.capabilities().searchable()).map(FieldDefinition::name).toList();
        if (raw.search() != null && searchable.isEmpty()) throw ResourceFailure.invalid();
        var sort = new ArrayList<Sort>();
        for (var text : raw.sorts()) {
            boolean descending = text.startsWith("-"); String name = descending ? text.substring(1) : text;
            if (!definition.field(name).capabilities().sortable()) throw ResourceFailure.invalid();
            sort.add(new Sort(name, descending));
        }
        // A stable server tie-breaker does not become an extra public order member.
        if (sort.stream().noneMatch(value -> value.field().equals(definition.idField()))) sort.add(new Sort(definition.idField(), false));
        var filters = new ArrayList<Filter>();
        for (var filter : raw.filters()) {
            var field = definition.field(filter.get("field").asString()); String operator = filter.get("operator").asString();
            if (!field.capabilities().filterable() || !field.type().operators().contains(operator)
                    || filter.get("value").isNull() && !Set.of("eq", "ne").contains(operator)) throw ResourceFailure.invalid();
            Object value = field.value(filter.get("value"), Set.of("contains", "startsWith").contains(operator));
            filters.add(new Filter(field.name(), operator, value));
        }
        return new ResourceQuery(raw.offset(), raw.limit(), raw.search(), searchable, List.copyOf(sort), List.copyOf(filters));
    }
    public boolean matches(Map<String, Object> row) {
        if (search != null && searchable.stream().noneMatch(field -> row.get(field) instanceof String text && text.contains(search))) return false;
        for (var filter : filters) {
            Object actual = row.get(filter.field()), expected = filter.value();
            boolean equal = actual == null ? expected == null : expected != null && compare(actual, expected) == 0;
            boolean match = switch (filter.operator()) {
                case "eq" -> equal;
                case "ne" -> !equal;
                case "contains" -> actual instanceof String text && text.contains((String) expected);
                case "startsWith" -> actual instanceof String text && text.startsWith((String) expected);
                case "lt" -> actual != null && compare(actual, expected) < 0;
                case "lte" -> actual != null && compare(actual, expected) <= 0;
                case "gt" -> actual != null && compare(actual, expected) > 0;
                case "gte" -> actual != null && compare(actual, expected) >= 0;
                default -> throw new IllegalStateException("Unvalidated query");
            };
            if (!match) return false;
        }
        return true;
    }
    public Comparator<Map<String, Object>> comparator() {
        return (left, right) -> {
            for (var criterion : sort) {
                Object a = left.get(criterion.field()), b = right.get(criterion.field());
                int compared = a == null ? b == null ? 0 : 1 : b == null ? -1 : compare(a, b);
                if (compared != 0) return criterion.descending() ? -Integer.signum(compared) : Integer.signum(compared);
            }
            return 0;
        };
    }
    @SuppressWarnings("unchecked") private static int compare(Object left, Object right) {
        return ((Comparable<Object>) left).compareTo(right);
    }
    private static BigInteger unsigned(String text) {
        if (text.length() > ResourceJson.NUMERIC_BOUND || !text.matches("[0-9]+")) throw ResourceFailure.invalid();
        return new BigInteger(text);
    }
    private static String single(Map<String, String[]> query, String name, String fallback) {
        var values = query.get(name);
        if (values == null) return fallback;
        if (values.length != 1) throw ResourceFailure.invalid();
        return values[0];
    }
}
