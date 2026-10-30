package com.alxarafe.erbas.resources;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tools.jackson.databind.JsonNode;

public enum ScalarType {
    BOOLEAN, STRING, TEXT, INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, DECIMAL,
    UUID, DATE, TIME, DATETIME, ENUM, BINARY;
    private static final DateTimeFormatter TIME_OUTPUT = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSS", Locale.ROOT);
    private static final int NANOS_PER_TIME_TICK = 100;
    private static final List<String> EQUALITY = List.of("eq", "ne");
    private static final List<String> ORDERED = List.of("eq", "ne", "lt", "lte", "gt", "gte");
    private static final List<String> TEXT_OPERATORS = List.of("eq", "ne", "lt", "lte", "gt", "gte", "contains", "startsWith");
    public boolean integer() { return switch (this) { case INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64 -> true; default -> false; }; }
    public boolean numeric() { return integer() || this == DECIMAL; }
    public boolean text() { return this == STRING || this == TEXT; }
    public boolean identifier() { return integer() || this == STRING || this == UUID; }
    public List<String> operators() {
        return text() ? TEXT_OPERATORS : numeric() || this == DATE || this == TIME || this == DATETIME ? ORDERED : EQUALITY;
    }
    public Object parse(JsonNode node) {
        try {
            if (node == null || node.isNull()) throw ResourceFailure.invalid();
            if (integer()) {
                if (!node.isIntegralNumber()) throw ResourceFailure.invalid();
                var number = new BigInteger(node.asString());
                int bits = switch (this) { case INT8, UINT8 -> 8; case INT16, UINT16 -> 16; case INT32, UINT32 -> 32; default -> 64; };
                boolean unsigned = this == UINT8 || this == UINT16 || this == UINT32;
                var minimum = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
                var maximum = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1).subtract(BigInteger.ONE);
                if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) throw ResourceFailure.invalid();
                return number;
            }
            if (this == DECIMAL) {
                if (!node.isNumber()) throw ResourceFailure.invalid();
                var decimal = new BigDecimal(node.asString());
                if (decimal.precision() > ResourceJson.NUMERIC_BOUND || Math.abs((long) decimal.scale()) > ResourceJson.NUMERIC_BOUND)
                    throw ResourceFailure.invalid();
                return decimal;
            }
            if (this == BOOLEAN) {
                if (!node.isBoolean()) throw ResourceFailure.invalid();
                return node.asBoolean();
            }
            if (!node.isString() || !ResourceJson.validText(node.asString())) throw ResourceFailure.invalid();
            String value = node.asString();
            return switch (this) {
                case STRING, TEXT, ENUM -> value;
                case UUID -> {
                    var id = java.util.UUID.fromString(value);
                    if (!id.toString().equalsIgnoreCase(value)) throw ResourceFailure.invalid();
                    yield id;
                }
                case DATE -> {
                    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw ResourceFailure.invalid();
                    yield LocalDate.parse(value);
                }
                case TIME -> {
                    if (!value.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,7})?")) throw ResourceFailure.invalid();
                    yield LocalTime.parse(value);
                }
                case DATETIME -> OffsetDateTime.parse(value).toInstant();
                case BINARY -> Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value));
                default -> throw ResourceFailure.invalid();
            };
        } catch (RuntimeException invalid) { throw ResourceFailure.invalid(); }
    }
    public JsonNode json(Object value) {
        if (value == null) return ResourceJson.JSON.nullNode();
        if (this == DECIMAL && !(value instanceof BigDecimal)) throw ResourceFailure.invalid();
        if (integer() && !(value instanceof BigInteger || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) throw ResourceFailure.invalid();
        if (this == TIME && value instanceof LocalTime time && time.getNano() % NANOS_PER_TIME_TICK != 0) throw ResourceFailure.invalid();
        Object wire = switch (this) {
            case UUID, DATE, DATETIME -> value.toString();
            case TIME -> TIME_OUTPUT.format((LocalTime) value);
            default -> value;
        };
        return ResourceJson.JSON.valueToTree(wire);
    }
}
