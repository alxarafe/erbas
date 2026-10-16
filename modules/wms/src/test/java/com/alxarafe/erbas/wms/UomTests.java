package com.alxarafe.erbas.wms;

import java.util.UUID;

import com.alxarafe.erbas.wms.domain.Uom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class UomTests {
    private static final UUID ID = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"A", "EA", "BOX", "PAL", "A1", "BOX10", "ABCDEFGHIJ"})
    void acceptsExactAsciiCodes(String code) {
        assertThat(new Uom(ID, code, "Each").code()).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "1A", "box", "Box", "A-B", "A_B", "ABCDEFGHIJK", " EA ", "KG\n", "ÉA", "A😀"})
    void rejectsInvalidCodesWithoutNormalization(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Uom(ID, code, "Each"));
    }

    @Test
    void trimsOnlyTheContractCharactersAtTheEdges() {
        assertThat(new Uom(ID, "EA", " \t\r\n Standard   Box \n\r\t ").description()).isEqualTo("Standard   Box");
        String preserved = "\u00a0\u2003x\u000by\u000c\u00a0";
        assertThat(new Uom(ID, "EA", preserved).description()).isEqualTo(preserved);
        assertThat(new Uom(ID, "EA", "X\t\r\nY").description()).isEqualTo("X\t\r\nY");
    }

    @Test
    void countsSupplementaryScalarsAfterTrimmingWithoutARawMaximum() {
        String maximum = "📦".repeat(Uom.DESCRIPTION_MAXIMUM);
        assertThat(new Uom(ID, "EA", " ".repeat(1000) + maximum + "\t".repeat(1000)).description()).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() -> new Uom(ID, "EA", maximum + "X"));
        assertThat(new Uom(ID, "EA", "📦").description()).isEqualTo("📦");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "x\u0000y", "\ud800", "\udc00", "\ud800x", "x\udc00"})
    void rejectsEmptyNulAndUnpairedSurrogates(String description) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Uom(ID, "EA", description));
    }

    @Test
    void identityMustBePresentAndNonNilWithoutPrescribingUuidVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Uom(null, "EA", "Each"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Uom(new UUID(0, 0), "EA", "Each"));
    }
}
