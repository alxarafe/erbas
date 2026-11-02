package com.alxarafe.erbas.assets;

import java.util.*;
import com.alxarafe.erbas.assets.domain.AssetLifecycle;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.navigation.NavigationRegistry;
import com.alxarafe.erbas.resources.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.assets.AssetResource.*;

class AssetResourceTests {
    @Test void resourceAndNavigationAreValidModuleContributions() {
        var modules = new ModuleRegistry(List.of(() -> List.of(new AssetsModule())));
        var resources = new ResourceRegistry(List.of(() -> List.of(definition())), List.of(), List.of(), modules);
        var navigation = new NavigationRegistry(List.of(() -> List.of(AssetResource.navigation())), modules, resources);
        assertThat(resources.definitions()).extracting(ResourceDefinition::id).containsExactly(RESOURCE);
        assertThat(navigation.contributions().getFirst().items().getFirst().children().getFirst().target())
                .isEqualTo(new com.alxarafe.erbas.navigation.NavigationContribution.ResourceTarget(RESOURCE));
    }
    @Test void permissionsAndActionsAreIndependent() {
        var definition = definition();
        assertThat(definition.operations()).containsEntry(ResourceOperation.list, READ).containsEntry(ResourceOperation.get, READ)
                .containsEntry(ResourceOperation.create, MAINTAIN).containsEntry(ResourceOperation.update, MAINTAIN).containsEntry(ResourceOperation.delete, MAINTAIN);
        assertThat(definition.actions()).extracting(ResourceActionDefinition::permission).containsExactly(MAINTAIN, SENSITIVE);
        assertThat(definition.actions()).allMatch(action -> action.scope() == ResourceActionDefinition.Scope.ENTITY && action.parameters().isEmpty());
    }
    @Test void contextualFormsAndColumnsDescribeTheRealFields() {
        var presentation = definition().contextPresentation();
        assertThat(presentation.list().columns()).extracting(ResourceContextPresentation.Column::field).containsExactly(CODE, NAME, SERIAL, LIFECYCLE, UPDATED);
        assertThat(presentation.create().tabs().getFirst().sections().getFirst().groups().getFirst().fields()).containsExactly(CODE, NAME, DESCRIPTION, SERIAL);
        assertThat(presentation.edit().actions()).containsExactly(COMPLETE, RETIRE);
        assertThat(presentation.view().actions()).containsExactly(COMPLETE, RETIRE);
        assertThat(definition().field(CODE).immutable()).isTrue();
        assertThat(definition().field(LIFECYCLE).readOnly()).isTrue();
    }
    @ParameterizedTest @EnumSource(AssetLifecycle.class)
    void lifecycleRulesAreClosed(AssetLifecycle state) {
        assertThat(state.mutable()).isEqualTo(state != AssetLifecycle.RETIRED);
        assertThat(state.deletable()).isEqualTo(state == AssetLifecycle.DRAFT);
        assertThat(state.canComplete()).isEqualTo(state == AssetLifecycle.DRAFT);
        assertThat(state.canRetire()).isEqualTo(state == AssetLifecycle.READY);
    }
    @Test void validationCountsScalarsAndRejectsBlankNamesWithoutNormalizing() {
        var field = definition().field(NAME);
        assertThat(field.value(ResourceJson.JSON.valueToTree("🙂".repeat(NAME_MAX)), false)).isEqualTo("🙂".repeat(NAME_MAX));
        for (String invalid : List.of("", " \t\n", "\u00a0", "x".repeat(NAME_MAX + 1)))
            assertThatThrownBy(() -> field.value(ResourceJson.JSON.valueToTree(invalid), false)).isInstanceOf(ResourceFailure.class);
        assertThat(definition().field(CODE).value(ResourceJson.JSON.valueToTree(" A "), false)).isEqualTo(" A ");
    }
    @Test void serverOwnedInputsAreRejectedAndPatchPreservesOmissionAndNull() {
        for (String name : List.of(ID, LIFECYCLE, CREATED, UPDATED))
            assertThatThrownBy(() -> ResourceRecords.input(definition().fields(), ResourceJson.JSON.valueToTree(Map.of(name, "client")), true))
                    .isInstanceOf(ResourceFailure.class);
        var input = ResourceRecords.input(definition().fields(), ResourceRecords.transport("{\"description\":null}"), false);
        assertThat(input).containsEntry(DESCRIPTION, null).doesNotContainKey(NAME);
    }
}
