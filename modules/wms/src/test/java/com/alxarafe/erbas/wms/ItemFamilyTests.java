package com.alxarafe.erbas.wms;

import java.util.UUID;

import com.alxarafe.erbas.wms.domain.ItemFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ItemFamilyTests {
    private static final UUID ID = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"A", "COLD", "COLD_CHAIN", "ZONE2", "A_1", "IS_FOOD"})
    void acceptsExactAsciiCodes(String code) {
        assertThat(new ItemFamily(ID, code, "Cold storage").code()).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "1A", "box", "Box", "A-B", "_A", "A B", "A\tB", " EA ", "KG\n", "ÉA", "A😀"})
    void rejectsInvalidCodesWithoutNormalization(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(ID, code, "Cold storage"));
    }

    @Test
    void acceptsMaximumCodeLengthAndRejectsOneMoreCharacter() {
        String maximum = "A".repeat(ItemFamily.CODE_MAXIMUM);
        assertThat(new ItemFamily(ID, maximum, "Cold storage").code()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(ID, maximum + "A", "Cold storage"));
    }

    @Test
    void acceptsBmpScalarBoundaries() {
        assertThat(new ItemFamily(ID, "A", "x").name()).isEqualTo("x");
        String maximum = "x".repeat(ItemFamily.NAME_MAXIMUM);
        assertThat(new ItemFamily(ID, "A", maximum).name()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(ID, "A", maximum + "x"));
    }

    @Test
    void trimsOnlyTheContractCharactersAtTheEdges() {
        assertThat(new ItemFamily(ID, "EA", " \t\r\n Standard   Box \n\r\t ").name()).isEqualTo("Standard   Box");
        String preserved = "\u00a0\u2003x\u000by\u000c\u00a0";
        assertThat(new ItemFamily(ID, "EA", preserved).name()).isEqualTo(preserved);
        assertThat(new ItemFamily(ID, "EA", "X\t\r\nY").name()).isEqualTo("X\t\r\nY");
    }

    @Test
    void countsSupplementaryScalarsAfterTrimmingWithoutARawMaximum() {
        String maximum = "📦".repeat(ItemFamily.NAME_MAXIMUM);
        assertThat(new ItemFamily(ID, "EA", " ".repeat(1000) + maximum + "\t".repeat(1000)).name()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(ID, "EA", maximum + "X"));
        assertThat(new ItemFamily(ID, "EA", "📦").name()).isEqualTo("📦");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "x\u0000y", "\ud800", "\udc00", "\ud800x", "x\udc00"})
    void rejectsEmptyNulAndUnpairedSurrogates(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(ID, "EA", name));
    }

    @Test
    void identityMustBePresentAndNonNilWithoutPrescribingUuidVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(null, "EA", "Cold storage"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemFamily(new UUID(0, 0), "EA", "Cold storage"));
    }
}
