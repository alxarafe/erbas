package com.alxarafe.erbas.resources;

import java.math.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Module-owned scalar declaration. Missing default and explicit null remain distinct. */
public record FieldDefinition(String name, ScalarType type, boolean nullable, boolean required, boolean readOnly,
        boolean immutable, JsonNode defaultValue, Constraints constraints, Capabilities capabilities,
        List<String> enumValues, List<Annotation> annotations) {
    public FieldDefinition {
        if (name == null || name.isBlank() || !ResourceJson.validText(name) || type == null || constraints == null || capabilities == null
                || enumValues == null || annotations == null) throw new IllegalArgumentException("Invalid field");
        enumValues = List.copyOf(enumValues); annotations = List.copyOf(annotations);
        if (enumValues.stream().anyMatch(v -> v == null || v.isEmpty() || !ResourceJson.validText(v))
                || enumValues.stream().distinct().count() != enumValues.size()
                || (type == ScalarType.ENUM) != !enumValues.isEmpty()
                || capabilities.searchable() && !type.text()) throw new IllegalArgumentException("Invalid field capabilities/enum");
        constraints.check(type);
        if (defaultValue != null) defaultValue = defaultValue.deepCopy();
    }
    @Override public JsonNode defaultValue() { return defaultValue == null ? null : defaultValue.deepCopy(); }
    public Object value(JsonNode raw, boolean fragment) {
        if (raw == null) throw ResourceFailure.invalid();
        if (raw.isNull()) { if (!nullable) throw ResourceFailure.invalid(); return null; }
        Object value = type.parse(raw);
        if (type == ScalarType.ENUM && !enumValues.contains(value)) throw ResourceFailure.invalid();
        if (!fragment) constraints.validate(type, value);
        return value;
    }
    public Map<String, Object> metadata(boolean parameter) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name); result.put("type", type); result.put("nullable", nullable); result.put("required", required);
        if (!parameter) { result.put("readOnly", readOnly); result.put("immutable", immutable); }
        if (defaultValue != null) result.put("default", defaultValue);
        result.put("constraints", constraints.metadata());
        if (!parameter) result.put("capabilities", Map.of("filterable", capabilities.filterable(), "sortable", capabilities.sortable(),
                "searchable", capabilities.searchable(), "filterOperators", capabilities.filterable() ? type.operators() : List.of()));
        result.put("enumValues", enumValues); result.put("annotations", annotations); return result;
    }
    public record Capabilities(boolean filterable, boolean sortable, boolean searchable) {
        public static final Capabilities NONE = new Capabilities(false, false, false);
    }
    public record Annotation(String vocabulary, String term, JsonNode value) {
        public Annotation {
            if (vocabulary == null || vocabulary.isBlank() || !ResourceJson.validText(vocabulary)
                    || term == null || term.isBlank() || !ResourceJson.validText(term) || value == null || value.isNull()
                    || !(value.isString() || value.isNumber() || value.isBoolean() || value.isArray()))
                throw new IllegalArgumentException("Invalid annotation");
            if (value.isString() && !ResourceJson.validText(value.asString())) throw new IllegalArgumentException("Invalid annotation text");
            if (value.isNumber()) new BigDecimal(value.asString());
            if (value.isArray()) for (var item : value) if (!item.isString() || item.asString().isEmpty() || !ResourceJson.validText(item.asString())) throw new IllegalArgumentException("Invalid annotation array");
            value = value.deepCopy();
        }
        @Override public JsonNode value() { return value.deepCopy(); }
    }
    public record Constraints(BigDecimal minimum, BigDecimal maximum, boolean exclusiveMinimum, boolean exclusiveMaximum,
            BigDecimal multipleOf, Integer precision, Integer scale, Integer minimumLength, Integer maximumLength, String pattern) {
        public static final Constraints NONE = new Constraints(null, null, false, false, null, null, null, null, null, null);
        void check(ScalarType type) {
            if (!type.numeric() && (minimum != null || maximum != null || multipleOf != null || precision != null || scale != null
                    || exclusiveMinimum || exclusiveMaximum)
                    || !(type.text() || type == ScalarType.ENUM) && (minimumLength != null || maximumLength != null || pattern != null)
                    || exclusiveMinimum && minimum == null || exclusiveMaximum && maximum == null
                    || minimum != null && maximum != null && (minimum.compareTo(maximum) > 0
                            || minimum.compareTo(maximum) == 0 && (exclusiveMinimum || exclusiveMaximum))
                    || multipleOf != null && multipleOf.signum() <= 0
                    || precision != null && precision < 0 || scale != null && scale < 0
                    || minimumLength != null && minimumLength < 0 || maximumLength != null && maximumLength < 0
                    || minimumLength != null && maximumLength != null && minimumLength > maximumLength)
                throw new IllegalArgumentException("Incompatible constraints");
            for (var bound : new BigDecimal[] {minimum, maximum, multipleOf}) if (bound != null
                    && (bound.precision() > ResourceJson.NUMERIC_BOUND || Math.abs((long) bound.scale()) > ResourceJson.NUMERIC_BOUND))
                throw new IllegalArgumentException("Numeric constraint exceeds transport bounds");
            if (pattern != null) Pattern.compile(pattern);
        }
        void validate(ScalarType type, Object value) {
            if (type.numeric()) {
                var number = value instanceof BigDecimal decimal ? decimal : new BigDecimal((BigInteger) value);
                if (minimum != null && (number.compareTo(minimum) < 0 || exclusiveMinimum && number.compareTo(minimum) == 0)
                        || maximum != null && (number.compareTo(maximum) > 0 || exclusiveMaximum && number.compareTo(maximum) == 0)
                        || multipleOf != null && number.remainder(multipleOf).signum() != 0
                        || precision != null && number.precision() > precision || scale != null && Math.max(0, number.scale()) > scale)
                    throw ResourceFailure.invalid();
            }
            if (value instanceof String text && (type.text() || type == ScalarType.ENUM)) {
                int length = text.codePointCount(0, text.length());
                if (minimumLength != null && length < minimumLength || maximumLength != null && length > maximumLength
                        || pattern != null && !Pattern.compile(pattern).matcher(text).find()) throw ResourceFailure.invalid();
            }
        }
        public Map<String, Object> metadata() {
            var map = new LinkedHashMap<String, Object>();
            if (minimum != null) map.put("minimum", minimum); if (maximum != null) map.put("maximum", maximum);
            if (minimum != null) map.put("exclusiveMinimum", exclusiveMinimum); if (maximum != null) map.put("exclusiveMaximum", exclusiveMaximum);
            if (multipleOf != null) map.put("multipleOf", multipleOf); if (precision != null) map.put("precision", precision);
            if (scale != null) map.put("scale", scale); if (minimumLength != null) map.put("minimumLength", minimumLength);
            if (maximumLength != null) map.put("maximumLength", maximumLength); if (pattern != null) map.put("pattern", pattern);
            return map;
        }
    }
}
