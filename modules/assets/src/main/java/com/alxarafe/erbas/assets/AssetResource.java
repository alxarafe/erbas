package com.alxarafe.erbas.assets;

import java.util.*;
import com.alxarafe.erbas.assets.domain.AssetLifecycle;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.navigation.NavigationContribution;

/** The complete public resource declaration belongs to Assets, never Core. */
public final class AssetResource {
    public static final String RESOURCE = "assets.asset";
    public static final String READ = "assets.read";
    public static final String MAINTAIN = "assets.maintain";
    public static final String SENSITIVE = "assets.actions.sensitive";
    public static final Set<String> PERMISSIONS = Set.of(READ, MAINTAIN, SENSITIVE);
    public static final String ID = "id", CODE = "code", NAME = "name", DESCRIPTION = "description",
            SERIAL = "serialNumber", LIFECYCLE = "lifecycle", CREATED = "createdAt", UPDATED = "updatedAt";
    public static final String COMPLETE = "complete", RETIRE = "retire";
    public static final int CODE_MAX = 64, NAME_MAX = 255, SERIAL_MAX = 255;
    private static final String NONBLANK = "[^\\p{javaWhitespace}\\p{Z}]";
    private static final String MAIN = "main";
    private AssetResource() { }

    public static ResourceDefinition definition() {
        var fields = List.of(
                field(ID, ScalarType.UUID, false, true, true, false, null, false, false, false, List.of()),
                field(CODE, ScalarType.STRING, false, true, false, true, CODE_MAX, true, true, true, List.of()),
                field(NAME, ScalarType.STRING, false, true, false, false, NAME_MAX, false, true, true, List.of()),
                field(DESCRIPTION, ScalarType.TEXT, true, false, false, false, null, false, false, false, List.of()),
                field(SERIAL, ScalarType.STRING, true, false, false, false, SERIAL_MAX, false, false, false, List.of()),
                field(LIFECYCLE, ScalarType.ENUM, false, true, true, false, null, true, false, false,
                        Arrays.stream(AssetLifecycle.values()).map(Enum::name).toList()),
                field(CREATED, ScalarType.DATETIME, false, true, true, false, null, false, true, false, List.of()),
                field(UPDATED, ScalarType.DATETIME, false, true, true, false, null, false, true, false, List.of()));
        var actions = List.of(action(COMPLETE, MAINTAIN), action(RETIRE, SENSITIVE));
        var view = form(List.of(CODE, NAME, DESCRIPTION, SERIAL, LIFECYCLE, CREATED, UPDATED), List.of(COMPLETE, RETIRE));
        var contextual = new ResourceContextPresentation(new ResourceContextPresentation.ListPresentation(
                List.of(CODE, NAME, SERIAL, LIFECYCLE, UPDATED).stream().map(name -> new ResourceContextPresentation.Column(name, null)).toList(), List.of()),
                form(List.of(CODE, NAME, DESCRIPTION, SERIAL), List.of()),
                form(List.of(CODE, NAME, DESCRIPTION, SERIAL, LIFECYCLE), List.of(COMPLETE, RETIRE)), view);
        return new ResourceDefinition(RESOURCE, "1.0.0", AssetsModule.ID, ID, fields,
                Map.of(ResourceOperation.list, READ, ResourceOperation.get, READ, ResourceOperation.create, MAINTAIN,
                        ResourceOperation.update, MAINTAIN, ResourceOperation.delete, MAINTAIN),
                List.of(), actions, List.of(), new ResourcePresentation(view), contextual);
    }
    private static FieldDefinition field(String name, ScalarType type, boolean nullable, boolean required, boolean readOnly,
            boolean immutable, Integer maximum, boolean filterable, boolean sortable, boolean searchable, List<String> values) {
        var constraints = new FieldDefinition.Constraints(null, null, false, false, null, null, null,
                name.equals(CODE) ? 1 : null, maximum, name.equals(NAME) ? NONBLANK : null);
        return new FieldDefinition(name, type, nullable, required, readOnly, immutable, null, constraints,
                new FieldDefinition.Capabilities(filterable, sortable, searchable), values, List.of());
    }
    private static ResourceActionDefinition action(String name, String permission) {
        return new ResourceActionDefinition(name, ResourceActionDefinition.Scope.ENTITY, false, permission, List.of(), List.of());
    }
    private static ResourcePresentation.Form form(List<String> fields, List<String> actions) {
        return new ResourcePresentation.Form(new ResourcePresentation.Behavior(ResourcePresentation.UnsavedChanges.CONFIRM_DISCARD), actions,
                List.of(new ResourcePresentation.Tab(MAIN, null, List.of(), List.of(new ResourcePresentation.Section(MAIN, null, false, false,
                        List.of(), List.of(new ResourcePresentation.Group(MAIN, null, fields)))))));
    }
    public static NavigationContribution navigation() {
        var leaf = new NavigationContribution.Entry(RESOURCE, "assets.navigation.assets", 10, null,
                new NavigationContribution.ResourceTarget(RESOURCE), List.of(), List.of());
        return new NavigationContribution(AssetsModule.ID, List.of(new NavigationContribution.Entry("assets", "assets.navigation.assets",
                20, null, null, List.of(leaf), List.of())));
    }
}
