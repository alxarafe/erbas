package com.alxarafe.erbas.resources;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Ordered optional layout declarations, without a rendering framework. */
public record ResourcePresentation(Form form) {
    public enum UnsavedChanges { CONFIRM_DISCARD, ALLOW_DISCARD }
    public record Behavior(UnsavedChanges unsavedChanges) { }
    public record Form(Behavior behavior, List<String> actions, List<Tab> tabs) {
        public Form { actions = List.copyOf(actions); tabs = List.copyOf(tabs); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tab(String id, String label, List<String> actions, List<Section> sections) {
        public Tab { actions = List.copyOf(actions); sections = List.copyOf(sections); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section(String id, String label, boolean collapsible, boolean initiallyCollapsed,
            List<String> actions, List<Group> groups) {
        public Section { actions = List.copyOf(actions); groups = List.copyOf(groups); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String id, String label, List<String> fields) {
        public Group { fields = List.copyOf(fields); }
    }
    void validate(List<String> fields, List<String> actions) {
        validateForm(form, fields, actions, false);
    }
    static void validateForm(Form form, List<String> fields, List<String> actions, boolean uniqueReferences) {
        if (form == null || form.behavior() == null || form.behavior().unsavedChanges() == null) throw new IllegalArgumentException("Invalid form");
        ResourceDefinition.unique(form.tabs().stream().map(Tab::id).toList());
        var usedFields = new ArrayList<String>(); var usedActions = new ArrayList<>(form.actions());
        for (var tab : form.tabs()) {
            validateLabel(tab.label());
            ResourceDefinition.unique(tab.sections().stream().map(Section::id).toList());
            usedActions.addAll(tab.actions());
            for (var section : tab.sections()) {
                validateLabel(section.label());
                ResourceDefinition.unique(section.groups().stream().map(Group::id).toList());
                usedActions.addAll(section.actions());
                if (section.initiallyCollapsed() && !section.collapsible()) throw new IllegalArgumentException("Invalid collapse state");
                for (var group : section.groups()) { validateLabel(group.label()); usedFields.addAll(group.fields()); }
            }
        }
        if (uniqueReferences) { ResourceDefinition.unique(usedFields); ResourceDefinition.unique(usedActions); }
        if (!fields.containsAll(usedFields) || !actions.containsAll(usedActions)) throw new IllegalArgumentException("Unknown presentation reference");
    }
    static void validateLabel(String label) {
        if (label != null && (label.isEmpty() || !ResourceJson.validText(label))) throw new IllegalArgumentException("Invalid presentation label");
    }
}
