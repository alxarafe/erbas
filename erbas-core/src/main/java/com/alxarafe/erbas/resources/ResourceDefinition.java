package com.alxarafe.erbas.resources;

import java.util.*;

/** Static server declaration. Permission names are deliberately absent from public metadata. */
public record ResourceDefinition(String id, String version, String moduleId, String idField,
        List<FieldDefinition> fields, Map<ResourceOperation, String> operations, List<RelationDefinition> relations,
        List<ResourceActionDefinition> actions, List<FieldDefinition.Annotation> annotations, ResourcePresentation presentation) {
    public ResourceDefinition {
        if (id == null || id.isBlank() || version == null || version.isBlank() || moduleId == null || moduleId.isBlank()
                || idField == null || fields == null || operations == null || relations == null || actions == null || annotations == null)
            throw new IllegalArgumentException("Incomplete resource definition");
        fields = List.copyOf(fields); operations = Map.copyOf(operations); relations = List.copyOf(relations);
        actions = List.copyOf(actions); annotations = List.copyOf(annotations);
        unique(fields.stream().map(FieldDefinition::name).toList()); unique(actions.stream().map(ResourceActionDefinition::name).toList());
        unique(relations.stream().map(RelationDefinition::name).toList());
        var identifier = fields.stream().filter(field -> field.name().equals(idField)).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing ID field"));
        if (!identifier.type().identifier() || identifier.nullable() || operations.values().stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Invalid identifier or operations");
        for (var field : fields) if (field.defaultValue() != null) field.value(field.defaultValue(), false);
        if (presentation != null) presentation.validate(fields.stream().map(FieldDefinition::name).toList(), actions.stream().map(ResourceActionDefinition::name).toList());
    }
    public FieldDefinition field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow(ResourceFailure::invalid);
    }
    static void unique(List<String> names) {
        if (names.stream().anyMatch(name -> name == null || name.isBlank()) || names.stream().distinct().count() != names.size())
            throw new IllegalArgumentException("Invalid or duplicate declaration identity");
    }
    public record RelationDefinition(String name, String targetResource, String localField, String targetField,
            String displayField, boolean required) { }
}
