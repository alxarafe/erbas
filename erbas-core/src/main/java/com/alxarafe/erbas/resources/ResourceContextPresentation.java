package com.alxarafe.erbas.resources;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Static recommendations only; field semantics and authorization remain in resource metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceContextPresentation(ListPresentation list, ResourcePresentation.Form create,
        ResourcePresentation.Form edit, ResourcePresentation.Form view) {
    public static final ResourceContextPresentation EMPTY = new ResourceContextPresentation(null, null, null, null);
    public record ListPresentation(List<Column> columns, List<String> actions) {
        public ListPresentation { columns = List.copyOf(columns); actions = List.copyOf(actions); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String field, String label) { }

    void validate(List<FieldDefinition> fields, List<ResourceActionDefinition> actions) {
        var names = fields.stream().map(FieldDefinition::name).toList();
        if (list != null) {
            var references = list.columns().stream().map(Column::field).toList();
            ResourceDefinition.unique(references); ResourceDefinition.unique(list.actions());
            if (!names.containsAll(references) || !actionNames(actions, Set.of(ResourceActionDefinition.Scope.COLLECTION,
                    ResourceActionDefinition.Scope.SELECTION)).containsAll(list.actions()))
                throw new IllegalArgumentException("Unknown or incompatible list reference");
            list.columns().forEach(column -> ResourcePresentation.validateLabel(column.label()));
        }
        validateForm(create, names, actions, ResourceActionDefinition.Scope.COLLECTION);
        validateForm(edit, names, actions, ResourceActionDefinition.Scope.ENTITY);
        validateForm(view, names, actions, ResourceActionDefinition.Scope.ENTITY);
    }
    private static void validateForm(ResourcePresentation.Form form, List<String> fields,
            List<ResourceActionDefinition> actions, ResourceActionDefinition.Scope scope) {
        if (form != null) ResourcePresentation.validateForm(form, fields, actionNames(actions, Set.of(scope)), true);
    }
    private static List<String> actionNames(List<ResourceActionDefinition> actions, Set<ResourceActionDefinition.Scope> scopes) {
        return actions.stream().filter(action -> scopes.contains(action.scope())).map(ResourceActionDefinition::name).toList();
    }
}
