package com.alxarafe.erbas.wms;

import java.util.UUID;

import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class StorageAttributeTests {
    private static final UUID ID = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"A", "COLD", "COLD_CHAIN", "ZONE2", "A_1", "IS_FOOD"})
    void acceptsExactAsciiCodes(String code) {
        assertThat(new StorageAttribute(ID, code, "Cold storage").code()).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "1A", "box", "Box", "A-B", "_A", "A B", "A\tB", " EA ", "KG\n", "ÉA", "A😀"})
    void rejectsInvalidCodesWithoutNormalization(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(ID, code, "Cold storage"));
    }

    @Test
    void acceptsMaximumCodeLengthAndRejectsOneMoreCharacter() {
        String maximum = "A".repeat(StorageAttribute.CODE_MAXIMUM);
        assertThat(new StorageAttribute(ID, maximum, "Cold storage").code()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(ID, maximum + "A", "Cold storage"));
    }

    @Test
    void acceptsBmpScalarBoundaries() {
        assertThat(new StorageAttribute(ID, "A", "x").name()).isEqualTo("x");
        String maximum = "x".repeat(StorageAttribute.NAME_MAXIMUM);
        assertThat(new StorageAttribute(ID, "A", maximum).name()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(ID, "A", maximum + "x"));
    }

    @Test
    void trimsOnlyTheContractCharactersAtTheEdges() {
        assertThat(new StorageAttribute(ID, "EA", " \t\r\n Standard   Box \n\r\t ").name()).isEqualTo("Standard   Box");
        String preserved = "\u00a0\u2003x\u000by\u000c\u00a0";
        assertThat(new StorageAttribute(ID, "EA", preserved).name()).isEqualTo(preserved);
        assertThat(new StorageAttribute(ID, "EA", "X\t\r\nY").name()).isEqualTo("X\t\r\nY");
    }

    @Test
    void countsSupplementaryScalarsAfterTrimmingWithoutARawMaximum() {
        String maximum = "📦".repeat(StorageAttribute.NAME_MAXIMUM);
        assertThat(new StorageAttribute(ID, "EA", " ".repeat(1000) + maximum + "\t".repeat(1000)).name()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(ID, "EA", maximum + "X"));
        assertThat(new StorageAttribute(ID, "EA", "📦").name()).isEqualTo("📦");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "x\u0000y", "\ud800", "\udc00", "\ud800x", "x\udc00"})
    void rejectsEmptyNulAndUnpairedSurrogates(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(ID, "EA", name));
    }

    @Test
    void identityMustBePresentAndNonNilWithoutPrescribingUuidVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(null, "EA", "Cold storage"));
        assertThatIllegalArgumentException().isThrownBy(() -> new StorageAttribute(new UUID(0, 0), "EA", "Cold storage"));
    }
}
