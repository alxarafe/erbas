package com.alxarafe.erbas.resources;

import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ScalarTests {
    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "BOOLEAN|true|true", "STRING|\"hello\"|\"hello\"", "TEXT|\"line\\ntext\"|\"line\\ntext\"",
            "INT8|-128|-128", "UINT8|255|255", "INT16|-32768|-32768", "UINT16|65535|65535",
            "INT32|-2147483648|-2147483648", "UINT32|4294967295|4294967295",
            "INT64|9223372036854775807|9223372036854775807", "DECIMAL|12345678901234567890.123456789|12345678901234567890.123456789",
            "UUID|\"ABCDEFAB-1234-1234-1234-ABCDEFABCDEF\"|\"abcdefab-1234-1234-1234-abcdefabcdef\"",
            "DATE|\"2024-02-29\"|\"2024-02-29\"", "TIME|\"12:34:56.1234567\"|\"12:34:56.1234567\"",
            "DATETIME|\"2026-10-30T12:00:00+02:00\"|\"2026-10-30T10:00:00Z\"", "ENUM|\"ACTIVE\"|\"ACTIVE\"",
            "BINARY|\"aGVsbG8=\"|\"aGVsbG8=\""})
    void everyScalarNormalizesWithoutNumericPrecisionLoss(ScalarType type, String input, String expected) {
        var actual = type.json(type.parse(ResourceJson.JSON.readTree(input)));
        assertThat(actual).isEqualTo(ResourceJson.JSON.readTree(expected));
    }
    @ParameterizedTest @CsvSource(delimiter = '|', value = {"INT8|127", "UINT8|0", "INT16|32767", "UINT16|0",
            "INT32|2147483647", "UINT32|0", "INT64|-9223372036854775808"})
    void oppositeIntegerBoundariesAreAlsoRepresentable(ScalarType type, String value) {
        assertThat(type.parse(ResourceJson.JSON.readTree(value))).isEqualTo(new BigInteger(value));
    }
    @Test void timeWithoutFractionAndExactNumericTransportBoundNormalizeCorrectly() {
        assertThat(ScalarType.TIME.json(ScalarType.TIME.parse(ResourceJson.JSON.readTree("\"12:00:00\""))).asString()).isEqualTo("12:00:00.0000000");
        String decimal = "0." + "1".repeat(ResourceJson.NUMERIC_BOUND);
        var parsed = ResourceJson.object("{\"number\":" + decimal + "}").get("number");
        assertThat(ScalarType.DECIMAL.parse(parsed)).isEqualTo(new BigDecimal(decimal));
        assertThatThrownBy(() -> ScalarType.DECIMAL.json(0.1d)).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> ScalarType.TIME.json(java.time.LocalTime.of(1, 2, 3, 123456789))).isInstanceOf(ResourceFailure.class);
    }
    @Test void defaultsPreserveExplicitNullAndRequiredMeansPresence() {
        var field = new FieldDefinition("optional", ScalarType.STRING, true, true, false, false, ResourceJson.JSON.valueToTree("default"),
                FieldDefinition.Constraints.NONE, FieldDefinition.Capabilities.NONE, List.of(), List.of());
        assertThat(ResourceRecords.input(List.of(field), ResourceJson.object("{}"), true).get("optional")).isEqualTo("default");
        assertThat(ResourceRecords.input(List.of(field), ResourceJson.object("{\"optional\":null}"), true)).containsKey("optional").containsEntry("optional", null);
        assertThat(ResourceRecords.input(List.of(field), ResourceJson.object("{}"), false)).isEmpty();
    }
    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "INT8|-129", "INT8|128", "UINT8|-1", "UINT8|256", "INT16|-32769", "INT16|32768",
            "UINT16|-1", "UINT16|65536", "INT32|-2147483649", "INT32|2147483648",
            "UINT32|-1", "UINT32|4294967296", "INT64|-9223372036854775809", "INT64|9223372036854775808",
            "INT64|1.0", "DECIMAL|\"1.2\"", "BOOLEAN|1", "STRING|true", "STRING|\"\\u0000\"",
            "STRING|\"\\ud800\"", "TEXT|\"\\udfff\"", "UUID|\"1-1-1-1-1\"", "DATE|\"2023-02-29\"",
            "TIME|\"12:00\"", "TIME|\"12:00:00.12345678\"", "DATETIME|\"2026-10-30T12:00:00\"", "BINARY|\"?\""})
    void rejectsExactBoundariesWrongTypesAndInvalidText(ScalarType type, String input) {
        assertThatThrownBy(() -> type.parse(ResourceJson.JSON.readTree(input))).isInstanceOf(ResourceFailure.class);
    }
    @Test void decimalConstraintsAreExactAndNumericTransportIsBounded() {
        var constraints = new FieldDefinition.Constraints(new BigDecimal("0.1"), new BigDecimal("1.0"), true, false,
                new BigDecimal("0.1"), 3, 1, null, null, null);
        var field = field(ScalarType.DECIMAL, constraints, List.of());
        assertThat(field.value(ResourceJson.JSON.readTree("0.3"), false)).isEqualTo(new BigDecimal("0.3"));
        for (var value : List.of("0.1", "1.1", "0.35")) assertThatThrownBy(() -> field.value(ResourceJson.JSON.readTree(value), false)).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> ScalarType.DECIMAL.parse(ResourceJson.JSON.readTree("1e4097"))).isInstanceOf(ResourceFailure.class);
    }
    @Test void textEnumConstraintsAndRegistrationAreValidated() {
        var constraints = new FieldDefinition.Constraints(null, null, false, false, null, null, null, 2, 3, "^[A-Z]+$");
        var field = field(ScalarType.ENUM, constraints, List.of("OK", "BAD"));
        assertThat(field.value(ResourceJson.JSON.readTree("\"OK\""), false)).isEqualTo("OK");
        for (var text : List.of("NO", "")) assertThatThrownBy(() -> field.value(ResourceJson.JSON.valueToTree(text), false)).isInstanceOf(ResourceFailure.class);
        assertThatThrownBy(() -> field(ScalarType.INT32, constraints, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    private FieldDefinition field(ScalarType type, FieldDefinition.Constraints constraints, List<String> values) {
        return new FieldDefinition("value", type, false, true, false, false, null, constraints,
                FieldDefinition.Capabilities.NONE, values, List.of());
    }
}
