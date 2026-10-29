package com.alxarafe.erbas.capabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CapabilityTests {
    static final String OWNER = "extension.owner";
    static final String FIRST = "extension.catalogue.first";
    static final String SECOND = "extension.catalogue.second";
    static final String PERMISSION = "extension.read";
    static final Capability.SchemaReference INPUT = reference("Input");
    static final Capability.SchemaReference OUTPUT = reference("Output");
    static final CapabilitySchemaCatalog SCHEMAS = new CapabilitySchemaCatalog(Set.of("Input", "Output"));
    static Capability.SchemaReference reference(String name) {
        return new Capability.SchemaReference(Capability.SchemaReference.PREFIX + name);
    }
    static Capability capability(String id, List<String> permissions) {
        return new Capability(id, OWNER, "1.0.0", "List catalogue", "Read an existing catalogue.",
                Capability.READ, INPUT, OUTPUT, permissions);
    }

    @Test void immutableDeclarationDefensivelyCopiesPermissions() {
        var permissions = new ArrayList<>(List.of(PERMISSION));
        var declaration = capability(FIRST, permissions);
        permissions.clear();
        assertThat(declaration.requiredPermissions()).containsExactly(PERMISSION);
        assertThatThrownBy(() -> declaration.requiredPermissions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"a.b.c", "a1.b2.c3", "a-b.c-d.e-f"})
    void acceptsExactIdGrammar(String id) { assertThat(capability(id, List.of()).id()).isEqualTo(id); }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"A.b.c", "a..c", "a.b", "a.b.c.d", "a.b.c\n", "a.b.c ", "1a.b.c", "a-.b.c", "a.b_c.d"})
    void rejectsInvalidId(String id) {
        assertThatThrownBy(() -> capability(id, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"01.0.0", "1.0", "1.0.0-rc1", "1.0.0+build", "1.0.0\n", "1.0.-1"})
    void rejectsInvalidVersion(String version) {
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, version, "Name", "Description", Capability.READ,
                INPUT, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptsZeroVersionAndWriteClassificationWithoutAnyExecution() {
        assertThat(new Capability(FIRST, OWNER, "0.0.0", "Name", "Description", Capability.WRITE,
                INPUT, OUTPUT, List.of()).effect()).isEqualTo(Capability.WRITE);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n", "\u00a0", "\u2003", "\ufeff"})
    void rejectsMissingModuleAndText(String value) {
        assertThatThrownBy(() -> new Capability(FIRST, value, "1.0.0", "Name", "Description", Capability.READ,
                INPUT, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, "1.0.0", value, "Description", Capability.READ,
                INPUT, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, "1.0.0", "Name", value, Capability.READ,
                INPUT, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"READ", "execute", "read\n"})
    void rejectsInvalidEffect(String effect) {
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, "1.0.0", "Name", "Description", effect,
                INPUT, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"https://example.invalid/schema", "#/components/schemas/", "#/components/schemas/1Bad",
            "#/components/schemas/Input/other", "#/components/schemas/Input\n", "{\"type\":\"object\"}"})
    void rejectsNonlocalOrMalformedSchemaReference(String ref) {
        assertThatThrownBy(() -> new Capability.SchemaReference(ref)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresBothSchemaReferencesAndPermissions() {
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, "1.0.0", "Name", "Description", Capability.READ,
                null, OUTPUT, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Capability(FIRST, OWNER, "1.0.0", "Name", "Description", Capability.READ,
                INPUT, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capability(FIRST, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsDuplicateUnorderedAndBlankPermissions() {
        for (var permissions : List.of(List.of(PERMISSION, PERMISSION), List.of("z.read", "a.read"),
                List.of(""), List.of(" \t"))) {
            assertThatThrownBy(() -> capability(FIRST, permissions)).isInstanceOf(IllegalArgumentException.class);
        }
        var nullPermission = new ArrayList<String>(); nullPermission.add(null);
        assertThatThrownBy(() -> capability(FIRST, nullPermission)).isInstanceOf(IllegalArgumentException.class);
        assertThat(capability(FIRST, List.of("a.read", "z.read")).requiredPermissions()).containsExactly("a.read", "z.read");
    }
}
