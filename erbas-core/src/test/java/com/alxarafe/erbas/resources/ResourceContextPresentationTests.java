package com.alxarafe.erbas.resources;

import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.http.ResourceController;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class ResourceContextPresentationTests {
    private static final String FIELD = "name";
    private static final String OTHER_FIELD = "code";
    private static final String ACTION = "placed-action";
    private static final String UI = "UI";
    private static final String HIDDEN = "Hidden";
    private static final String FORM_EXCLUDED = "FormExcluded";
    private static final UserIdentity USER = new UserIdentity(7, "presentation@example.test", true, false);
    private static final ResourcePresentation.Behavior BEHAVIOR = new ResourcePresentation.Behavior(ResourcePresentation.UnsavedChanges.CONFIRM_DISCARD);
    private final ResourceDefinition base = ResourceConformanceConfiguration.definition();

    private ResourceDefinition definition(ResourceContextPresentation contextual) {
        return definition(contextual, base.fields(), base.actions(), base.presentation());
    }
    private ResourceDefinition definition(ResourceContextPresentation contextual, List<FieldDefinition> fields,
            List<ResourceActionDefinition> actions, ResourcePresentation legacy) {
        return new ResourceDefinition(base.id(), base.version(), base.moduleId(), base.idField(), fields,
                base.operations(), base.relations(), actions, base.annotations(), legacy, contextual);
    }
    private ResourcePresentation.Group group(String id, String... fields) {
        return new ResourcePresentation.Group(id, null, List.of(fields));
    }
    private ResourcePresentation.Section section(String id, ResourcePresentation.Group... groups) {
        return new ResourcePresentation.Section(id, null, false, false, List.of(), List.of(groups));
    }
    private ResourcePresentation.Tab tab(String id, ResourcePresentation.Section... sections) {
        return new ResourcePresentation.Tab(id, null, List.of(), List.of(sections));
    }
    private ResourcePresentation.Form form(String... fields) {
        return new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of(tab("same", section("same", group("same", fields)))));
    }
    private ResourceContextPresentation forms(ResourcePresentation.Form form) {
        return new ResourceContextPresentation(null, form, form, form);
    }
    private ResourceContextPresentation listing(List<ResourceContextPresentation.Column> columns, List<String> actions) {
        return new ResourceContextPresentation(new ResourceContextPresentation.ListPresentation(columns, actions), null, null, null);
    }
    private ResourceActionDefinition action(ResourceActionDefinition.Scope scope) {
        return new ResourceActionDefinition(ACTION, scope, scope == ResourceActionDefinition.Scope.SELECTION,
                "test.presentation.execute", List.of(), List.of());
    }
    private ResourceController controller(ResourceDefinition definition) {
        var modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
            public ModuleDescriptor descriptor() { return new ModuleDescriptor(base.moduleId(), ModuleApi.CURRENT_VERSION, List.of()); }
            public void initialize() { }
        })));
        var registry = new ResourceRegistry(List.of(() -> List.of(definition)), List.of(), List.of(), modules);
        var runtime = new ResourceRuntime(registry, null, id -> Optional.of(USER), List.of());
        return new ResourceController(registry, runtime, new ResourceMetadata(registry, runtime), new ResourceActions(registry, runtime));
    }
    private MockHttpServletRequest request(String etag) {
        var request = new MockHttpServletRequest("GET", ResourceMetadata.ROOT + "/" + base.id() + ResourceController.PRESENTATION_SUFFIX);
        if (etag != null) request.addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        return request;
    }
    private JsonNode projection(ResourceDefinition definition) {
        return ResourceJson.JSON.valueToTree(new ResourceMetadata(null, null).contextPresentation(definition));
    }
    @Test void legacyResourceReturnsClosedEmptyPresentation() {
        var response = controller(base).presentation(USER, base.id(), request(null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = ResourceJson.JSON.valueToTree(response.getBody());
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("id", "definitionHash", "presentation");
        assertThat(body.get("id").asString()).isEqualTo(base.id());
        assertThat(body.get("definitionHash").asString()).isNotBlank();
        assertThat(body.get("presentation").isObject()).isTrue();
        assertThat(body.get("presentation").isEmpty()).isTrue();
    }
    @Test void listIsClosedAndKeepsDeclaredColumnOrderAndOptionalLabels() {
        var definition = definition(listing(List.of(new ResourceContextPresentation.Column(FIELD, "Display name"),
                new ResourceContextPresentation.Column(OTHER_FIELD, null)), List.of()));
        var layout = projection(definition).get("presentation").get("list");
        assertThat(layout.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("columns", "actions");
        assertThat(layout.get("columns")).extracting(node -> node.get("field").asString()).containsExactly(FIELD, OTHER_FIELD);
        assertThat(layout.get("columns").get(0).properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("field", "label");
        assertThat(layout.get("columns").get(1).properties()).extracting(Map.Entry::getKey).containsExactly("field");
        assertThat(definition.fields()).isEqualTo(base.fields()); assertThat(definition.operations()).isEqualTo(base.operations());
    }
    @Test void allFormContextsReuseTheExactLegacyHierarchyWithoutWrapper() {
        var form = form(FIELD, OTHER_FIELD); var contexts = forms(form);
        assertThat(contexts.create()).isSameAs(form); assertThat(contexts.edit()).isSameAs(form); assertThat(contexts.view()).isSameAs(form);
        var definition = definition(contexts, base.fields(), base.actions(), new ResourcePresentation(form));
        var wire = projection(definition).get("presentation");
        var legacy = ResourceJson.JSON.valueToTree(definition.presentation());
        assertThat(legacy.properties()).extracting(Map.Entry::getKey).containsExactly("form");
        for (String context : List.of("create", "edit", "view")) assertThat(wire.get(context)).isEqualTo(legacy.get("form"));
    }
    @Test void explicitEmptyContextsRemainPresentAndDifferFromMissing() {
        var emptyForm = new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of());
        var contexts = new ResourceContextPresentation(new ResourceContextPresentation.ListPresentation(List.of(), List.of()), emptyForm, null, null);
        var wire = projection(definition(contexts)).get("presentation");
        assertThat(wire.has("list")).isTrue(); assertThat(wire.get("list").get("columns").isEmpty()).isTrue();
        assertThat(wire.has("create")).isTrue(); assertThat(wire.get("create").get("tabs").isEmpty()).isTrue();
        assertThat(wire.has("edit")).isFalse(); assertThat(wire.has("view")).isFalse();
        assertThat(projection(base).get("definitionHash")).isNotEqualTo(projection(definition(contexts)).get("definitionHash"));
    }
    @Test void contextualChangesAlterSharedDefinitionIdentity() {
        var first = projection(definition(forms(form(FIELD))));
        var second = projection(definition(forms(form(OTHER_FIELD))));
        assertThat(first.get("id")).isEqualTo(second.get("id"));
        assertThat(first.get("definitionHash")).isNotEqualTo(second.get("definitionHash"));
    }
    @ParameterizedTest @ValueSource(strings = {"list", "create", "edit", "view"})
    void unknownAndDuplicateFieldsFailAtDefinitionConstruction(String context) {
        for (var fields : List.of(List.of("missing"), List.of(FIELD, FIELD))) {
            ResourcePresentation.Form form = new ResourcePresentation.Form(BEHAVIOR, List.of(),
                    List.of(tab("tab", section("section", group("one", fields.getFirst()),
                            group("two", fields.size() == 1 ? OTHER_FIELD : fields.getLast())))));
            var declaration = switch (context) {
                case "list" -> listing(fields.stream().map(name -> new ResourceContextPresentation.Column(name, name)).toList(), List.of());
                case "create" -> new ResourceContextPresentation(null, form, null, null);
                case "edit" -> new ResourceContextPresentation(null, null, form, null);
                default -> new ResourceContextPresentation(null, null, null, form);
            };
            assertThatThrownBy(() -> definition(declaration)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void duplicateListReferencesAreRejectedEvenWhenLabelsDiffer() {
        assertThatThrownBy(() -> definition(listing(List.of(new ResourceContextPresentation.Column(FIELD, "First"),
                new ResourceContextPresentation.Column(FIELD, "Second")), List.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @CsvSource({"list,COLLECTION,true", "list,SELECTION,true", "list,ENTITY,false",
            "create,COLLECTION,true", "create,ENTITY,false", "create,SELECTION,false",
            "edit,ENTITY,true", "edit,COLLECTION,false", "edit,SELECTION,false",
            "view,ENTITY,true", "view,COLLECTION,false", "view,SELECTION,false"})
    void actionScopesAreCheckedPerContext(String context, ResourceActionDefinition.Scope scope, boolean valid) {
        var form = new ResourcePresentation.Form(BEHAVIOR, List.of(ACTION), List.of());
        var contextual = switch (context) {
            case "list" -> listing(List.of(), List.of(ACTION));
            case "create" -> new ResourceContextPresentation(null, form, null, null);
            case "edit" -> new ResourceContextPresentation(null, null, form, null);
            default -> new ResourceContextPresentation(null, null, null, form);
        };
        if (valid) assertThat(definition(contextual, base.fields(), List.of(action(scope)), null).contextPresentation()).isEqualTo(contextual);
        else assertThatThrownBy(() -> definition(contextual, base.fields(), List.of(action(scope)), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(contextual)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actionReferencesAreUniqueAcrossAllFormPlacementsAndWithinList() {
        var form = new ResourcePresentation.Form(BEHAVIOR, List.of(ACTION), List.of(
                new ResourcePresentation.Tab("tab", null, List.of(ACTION), List.of())));
        assertThatThrownBy(() -> definition(new ResourceContextPresentation(null, null, form, null), base.fields(),
                List.of(action(ResourceActionDefinition.Scope.ENTITY)), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(listing(List.of(), List.of(ACTION, ACTION)), base.fields(),
                List.of(action(ResourceActionDefinition.Scope.COLLECTION)), null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void structuralIdsAreLocalAndContextsCanReuseFieldsIdsAndActions() {
        var form = new ResourcePresentation.Form(BEHAVIOR, List.of(ACTION), List.of(
                tab("first", section("same", group("same", FIELD)), section("other", group("same", OTHER_FIELD))),
                tab("second", section("same", group("same", "active")))));
        var contextual = new ResourceContextPresentation(null, null, form, form);
        assertThat(definition(contextual, base.fields(), List.of(action(ResourceActionDefinition.Scope.ENTITY)), null).contextPresentation()).isSameAs(contextual);
    }
    @ParameterizedTest @ValueSource(strings = {"tab", "section", "group"})
    void duplicateSiblingIdsFailRegistration(String level) {
        var group = group("same", FIELD); var section = section("same", group); var tab = tab("same", section);
        var form = switch (level) {
            case "tab" -> new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of(tab, tab));
            case "section" -> new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of(tab("tab", section, section)));
            default -> new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of(tab("tab", section("section", group, group))));
        };
        assertThatThrownBy(() -> definition(forms(form))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "\u0000", "\ud800"})
    void labelsRetainExistingTextValidation(String label) {
        assertThatThrownBy(() -> definition(listing(List.of(new ResourceContextPresentation.Column(FIELD, label)), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        var form = new ResourcePresentation.Form(BEHAVIOR, List.of(), List.of(new ResourcePresentation.Tab("tab", label, List.of(), List.of())));
        assertThatThrownBy(() -> definition(forms(form))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void staticHintsAndFieldAuthorityRemainUnchangedByPlacements() {
        var fields = base.fields().stream().map(field -> {
            var hints = field.name().equals(FIELD) ? List.of(new FieldDefinition.Annotation(UI, HIDDEN, ResourceJson.JSON.valueToTree(true)))
                    : field.name().equals(OTHER_FIELD) ? List.of(new FieldDefinition.Annotation(UI, FORM_EXCLUDED, ResourceJson.JSON.valueToTree(true))) : field.annotations();
            return new FieldDefinition(field.name(), field.type(), field.nullable(), field.required(), field.readOnly(), field.immutable(),
                    field.defaultValue(), field.constraints(), field.capabilities(), field.enumValues(), hints);
        }).toList();
        var contextual = new ResourceContextPresentation(new ResourceContextPresentation.ListPresentation(
                List.of(new ResourceContextPresentation.Column(FIELD, null), new ResourceContextPresentation.Column(OTHER_FIELD, null)), List.of()),
                form(OTHER_FIELD), form(OTHER_FIELD, FIELD), form(FIELD));
        var definition = definition(contextual, fields, base.actions(), null);
        assertThat(definition.fields()).isEqualTo(fields);
        assertThat(definition.field(OTHER_FIELD).immutable()).isTrue();
        assertThat(ResourceJson.JSON.writeValueAsString(definition.fields())).contains(HIDDEN, FORM_EXCLUDED);
        assertThatThrownBy(() -> ResourceRecords.input(fields, ResourceJson.object("{}"), true)).isInstanceOf(ResourceFailure.class);
        // Hints remain authoritative client-side exclusions, not server field permissions or redaction.
        assertThat(ResourceRecords.input(fields, ResourceJson.object("{\"name\":\"First\",\"code\":\"ONE\"}"), true)).containsKey(FIELD);
    }
    @Test void conditionalGetAuthenticatesAndResolvesBeforeCacheComparisonWithoutCrudGuards() {
        var controller = controller(definition(forms(form(FIELD))));
        var first = controller.presentation(USER, base.id(), request(null)); String etag = first.getHeaders().getETag();
        assertThat(etag).startsWith("\"").endsWith("\"");
        var cached = controller.presentation(USER, base.id(), request(etag));
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED); assertThat(cached.getBody()).isNull();
        assertThat(cached.getHeaders().getCacheControl()).isEqualTo("private, no-cache");
        assertThat(cached.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
        assertThatThrownBy(() -> controller.presentation(null, base.id(), request(etag))).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.unauthorized);
        var query = request(etag); query.addParameter("unknown", "1");
        assertThatThrownBy(() -> controller.presentation(USER, "missing", query)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.invalid_request);
        assertThatThrownBy(() -> controller.presentation(USER, "missing", request(etag))).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.resource_not_found);
    }
}
