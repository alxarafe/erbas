package com.alxarafe.erbas.resources;

import java.util.List;

public record ResourceActionDefinition(String name, Scope scope, boolean requiresSelection, String permission,
        List<FieldDefinition> parameters, List<FieldDefinition.Annotation> annotations) {
    public enum Scope { ENTITY, COLLECTION, SELECTION }
    public ResourceActionDefinition {
        if (name == null || name.isBlank() || scope == null || permission == null || permission.isBlank()
                || parameters == null || annotations == null || requiresSelection && scope != Scope.SELECTION)
            throw new IllegalArgumentException("Invalid resource action");
        parameters = List.copyOf(parameters); annotations = List.copyOf(annotations);
        ResourceDefinition.unique(parameters.stream().map(FieldDefinition::name).toList());
        for (var parameter : parameters) {
            if (parameter.readOnly() || parameter.immutable()) throw new IllegalArgumentException("Invalid action parameter");
            if (parameter.defaultValue() != null) parameter.value(parameter.defaultValue(), false);
        }
    }
}
