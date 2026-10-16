package com.alxarafe.erbas.wms;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.alxarafe.erbas.wms.application.FamilyAssociations;
import com.alxarafe.erbas.wms.application.FamilyAssociations.*;
import com.alxarafe.erbas.wms.domain.CatalogueValues;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class FamilyAssociationsTests {
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();
    private static final UUID ABSENT = UUID.randomUUID();
    private static final Map<UUID, StorageAttribute> ATTRIBUTES = Map.of(
            A, new StorageAttribute(A, "A", "A"), B, new StorageAttribute(B, "B", "B"),
            C, new StorageAttribute(C, "C", "C"), D, new StorageAttribute(D, "D", "D"));
    private static final Snapshot BEFORE = new Snapshot(true, ATTRIBUTES, Set.of(A, B));

    @Test
    void replacementIsExactEmptyAndIdempotent() {
        assertThat(evaluate(Kind.REPLACE, List.of(C), List.of()).desired()).containsExactly(C);
        assertThat(evaluate(Kind.REPLACE, List.of(), List.of()).desired()).isEmpty();
        assertThat(evaluate(Kind.REPLACE, List.of(B, A), List.of()).desired()).containsExactlyInAnyOrder(A, B);
        assertThat(BEFORE.associated()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void referencesPrecedeConflictsAndRetainEveryRequestLocation() {
        var command = new Command(Kind.PATCH, List.of(A, ABSENT), List.of(ABSENT));
        var result = FamilyAssociations.evaluate(FAMILY, command, new Snapshot(false, ATTRIBUTES, Set.of(A, B)));
        assertThat(result.issues()).containsExactly(new Issue(Problem.FAMILY_MISSING, FAMILY, Part.ADD, -1),
                new Issue(Problem.ATTRIBUTE_MISSING, ABSENT, Part.ADD, 1), new Issue(Problem.ATTRIBUTE_MISSING, ABSENT, Part.REMOVE, 0));
        assertThat(result.desired()).isEmpty();
    }

    @Test
    void allPatchConflictsUseTheOriginalStateWithoutApplyingValidAdditions() {
        var result = evaluate(Kind.PATCH, List.of(B, C), List.of(D));
        assertThat(result.issues()).containsExactly(new Issue(Problem.ALREADY_ASSOCIATED, B, Part.ADD, 0),
                new Issue(Problem.NOT_ASSOCIATED, D, Part.REMOVE, 0));
        assertThat(result.desired()).isEmpty();
        assertThat(BEFORE.associated()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void overlapIsNotASpecialStructuralConflict() {
        assertThat(evaluate(Kind.PATCH, List.of(A), List.of(A)).issues())
                .containsExactly(new Issue(Problem.ALREADY_ASSOCIATED, A, Part.ADD, 0));
        assertThat(evaluate(Kind.PATCH, List.of(C), List.of(C)).issues())
                .containsExactly(new Issue(Problem.NOT_ASSOCIATED, C, Part.REMOVE, 0));
        assertThat(evaluate(Kind.PATCH, List.of(ABSENT), List.of(ABSENT)).issues())
                .containsExactly(new Issue(Problem.ATTRIBUTE_MISSING, ABSENT, Part.ADD, 0), new Issue(Problem.ATTRIBUTE_MISSING, ABSENT, Part.REMOVE, 0));
    }

    @Test
    void creationDeletionAndStrictPatchesHaveDistinctOutcomes() {
        var created = evaluate(Kind.CREATE, List.of(C), List.of());
        assertThat(created.created()).isEqualTo(ATTRIBUTES.get(C));
        assertThat(created.desired()).containsExactlyInAnyOrder(A, B, C);
        assertThat(evaluate(Kind.CREATE, List.of(A), List.of()).issues().getFirst().problem()).isEqualTo(Problem.ALREADY_ASSOCIATED);
        assertThat(evaluate(Kind.DELETE, List.of(), List.of(C)).issues().getFirst().problem()).isEqualTo(Problem.ASSOCIATION_MISSING);
        assertThat(evaluate(Kind.DELETE, List.of(), List.of(A)).desired()).containsExactly(B);
        assertThat(evaluate(Kind.PATCH, List.of(C), List.of(A)).desired()).containsExactlyInAnyOrder(B, C);
        assertThat(evaluate(Kind.PATCH, List.of(C), List.of()).desired()).containsExactlyInAnyOrder(A, B, C);
        assertThat(evaluate(Kind.PATCH, List.of(), List.of(A)).desired()).containsExactly(B);
    }

    @Test
    void failedPutHasOnlyExistenceIssuesAndPreservesItsInputSnapshot() {
        var result = evaluate(Kind.REPLACE, List.of(C, ABSENT), List.of());
        assertThat(result.issues()).containsExactly(new Issue(Problem.ATTRIBUTE_MISSING, ABSENT, Part.ADD, 1));
        assertThat(BEFORE.associated()).containsExactlyInAnyOrder(A, B);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "00000000-0000-0000-0000-000000000000", "1-1-1-1-1", "ABCDEFAB-1234-1234-1234-123456789012", " abcdefab-1234-1234-1234-123456789012"})
    void canonicalPathIdentityRejectsCoercionsAndNil(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> CatalogueValues.identity(value));
    }

    @Test
    void canonicalIdentityDoesNotPrescribeVersion() {
        for (String value : List.of("abcdefab-1234-1234-1234-123456789012", "abcdefab-1234-9234-1234-123456789012")) {
            assertThat(CatalogueValues.identity(value).toString()).isEqualTo(value);
        }
    }

    private static Result evaluate(Kind kind, List<UUID> add, List<UUID> remove) {
        return FamilyAssociations.evaluate(FAMILY, new Command(kind, add, remove), BEFORE);
    }
}
