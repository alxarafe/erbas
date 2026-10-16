package com.alxarafe.erbas.wms.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.alxarafe.erbas.adapter.http.ContractError;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.modularity.http.ModuleEndpoint;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.FamilyAssociations;
import com.alxarafe.erbas.wms.application.FamilyAssociations.*;
import com.alxarafe.erbas.wms.domain.CatalogueValues;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ModuleEndpoint(WmsModule.ID)
public class FamilyAssociationController {
    public static final String ASSOCIATION_SEGMENT = "storage-attributes";
    public static final String ASSOCIATIONS_PATH = ItemFamilyController.ITEM_FAMILIES_PATH + "/{familyId}/" + ASSOCIATION_SEGMENT;
    public static final String SINGLE_PATH = ASSOCIATIONS_PATH + "/{storageAttributeId}";
    public static final String ATTRIBUTE_ID = "storageAttributeId";
    public static final String ATTRIBUTE_IDS = "storageAttributeIds";
    public static final String ADD = "add";
    public static final String REMOVE = "remove";
    public static final String FAMILY_RESOURCE = "wms_item_family";
    public static final String ATTRIBUTE_RESOURCE = "wms_storage_attribute";
    public static final String RELATION_RESOURCE = "wms_item_family_storage_attribute";
    public static final String FAMILY_MISSING = "wms_item_family_not_found";
    public static final String ATTRIBUTE_MISSING = "wms_storage_attribute_not_found";
    public static final String RELATION_MISSING = "wms_item_family_storage_attribute_not_found";
    public static final String RESOURCES_MISSING = "wms_item_family_storage_attributes_not_found";
    public static final String RELATION_CONFLICT = "wms_item_family_storage_attribute_conflict";
    public static final String RELATIONS_CONFLICT = "wms_item_family_storage_attributes_conflict";
    public static final String ALREADY_EXISTS = "wms_item_family_storage_attribute_already_exists";
    public static final String NOT_ASSOCIATED = "wms_item_family_storage_attribute_not_associated";
    private final FamilyAssociations associations;

    public FamilyAssociationController(FamilyAssociations associations) { this.associations = associations; }

    @GetMapping(path = ASSOCIATIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@PathVariable String familyId, @RequestParam MultiValueMap<String, String> query) {
        var id = identity(familyId);
        var page = CatalogueHttp.pagination(query);
        if (id.isEmpty() || page.isEmpty()) return invalid();
        var applied = page.orElseThrow();
        var result = associations.list(id.orElseThrow(), applied.offset(), applied.limit());
        if (!result.familyExists()) return failure(Kind.REPLACE, List.of(new Issue(Problem.FAMILY_MISSING, id.orElseThrow(), Part.ADD, -1)));
        var window = result.window();
        return CatalogueHttp.json(HttpStatus.OK, new PagedCollection<>(window.items(), applied.offset(), applied.limit(), window.total(), CatalogueHttp.CODE_ASCENDING));
    }

    @PostMapping(path = ASSOCIATIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@PathVariable String familyId, @RequestBody String body, @RequestParam MultiValueMap<String, String> query) {
        return change(familyId, body, query, Kind.CREATE);
    }

    @PutMapping(path = ASSOCIATIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> replace(@PathVariable String familyId, @RequestBody String body, @RequestParam MultiValueMap<String, String> query) {
        return change(familyId, body, query, Kind.REPLACE);
    }

    @PatchMapping(path = ASSOCIATIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> patch(@PathVariable String familyId, @RequestBody String body, @RequestParam MultiValueMap<String, String> query) {
        return change(familyId, body, query, Kind.PATCH);
    }

    @DeleteMapping(path = SINGLE_PATH)
    public ResponseEntity<?> delete(@PathVariable String familyId, @PathVariable String storageAttributeId, @RequestParam MultiValueMap<String, String> query) {
        var family = identity(familyId);
        var attribute = identity(storageAttributeId);
        if (!query.isEmpty() || family.isEmpty() || attribute.isEmpty()) return invalid();
        return execute(family.orElseThrow(), new Command(Kind.DELETE, List.of(), List.of(attribute.orElseThrow())));
    }

    private ResponseEntity<?> change(String familyId, String body, MultiValueMap<String, String> query, Kind kind) {
        var family = identity(familyId);
        var command = command(body, kind);
        if (!query.isEmpty() || family.isEmpty() || command.isEmpty()) return invalid();
        return execute(family.orElseThrow(), command.orElseThrow());
    }

    private ResponseEntity<?> execute(UUID familyId, Command command) {
        var result = associations.change(familyId, command);
        if (!result.issues().isEmpty()) return failure(command.kind(), result.issues());
        if (command.kind() == Kind.CREATE) {
            return ResponseEntity.created(URI.create(ItemFamilyController.ITEM_FAMILIES_PATH + "/" + familyId + "/" + ASSOCIATION_SEGMENT + "/" + result.created().id()))
                    .contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                    .body(new CreatedAssociation(familyId, result.created()));
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static Optional<Command> command(String body, Kind kind) {
        var object = CatalogueHttp.object(body);
        if (object.isEmpty()) return Optional.empty();
        var value = object.orElseThrow();
        if (kind == Kind.CREATE) {
            if (value.size() != 1 || !value.has(ATTRIBUTE_ID) || !value.get(ATTRIBUTE_ID).isString()) return Optional.empty();
            return identity(value.get(ATTRIBUTE_ID).asString()).map(id -> new Command(kind, List.of(id), List.of()));
        }
        if (kind == Kind.REPLACE) {
            if (value.size() != 1 || !value.has(ATTRIBUTE_IDS)) return Optional.empty();
            return identifiers(value.get(ATTRIBUTE_IDS)).map(ids -> new Command(kind, ids, List.of()));
        }
        if (value.size() == 0 || value.size() > 2 || value.propertyNames().stream().anyMatch(name -> !name.equals(ADD) && !name.equals(REMOVE))) return Optional.empty();
        var add = value.has(ADD) ? identifiers(value.get(ADD)) : Optional.of(List.<UUID>of());
        var remove = value.has(REMOVE) ? identifiers(value.get(REMOVE)) : Optional.of(List.<UUID>of());
        if (add.isEmpty() || remove.isEmpty() || add.orElseThrow().isEmpty() && remove.orElseThrow().isEmpty()) return Optional.empty();
        return Optional.of(new Command(kind, add.orElseThrow(), remove.orElseThrow()));
    }

    private static Optional<List<UUID>> identifiers(JsonNode value) {
        if (!value.isArray()) return Optional.empty();
        var ids = new ArrayList<UUID>();
        var seen = new HashSet<UUID>();
        for (var element : value) {
            if (!element.isString()) return Optional.empty();
            var id = identity(element.asString());
            if (id.isEmpty() || !seen.add(id.orElseThrow())) return Optional.empty();
            ids.add(id.orElseThrow());
        }
        return Optional.of(ids);
    }

    private static Optional<UUID> identity(String value) {
        try { return Optional.of(CatalogueValues.identity(value)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static ResponseEntity<?> invalid() { return CatalogueHttp.error(HttpStatus.BAD_REQUEST, CatalogueHttp.INVALID_REQUEST); }

    private static ResponseEntity<?> failure(Kind kind, List<Issue> issues) {
        boolean missing = issues.stream().anyMatch(issue -> switch (issue.problem()) {
            case FAMILY_MISSING, ATTRIBUTE_MISSING, ASSOCIATION_MISSING -> true;
            default -> false;
        });
        var details = issues.stream().map(issue -> new ContractError.Detail(code(issue.problem()),
                switch (issue.problem()) {
                    case FAMILY_MISSING -> FAMILY_RESOURCE;
                    case ASSOCIATION_MISSING -> RELATION_RESOURCE;
                    default -> ATTRIBUTE_RESOURCE;
                }, issue.id().toString(), pointer(kind, issue))).toList();
        String top = missing ? (issues.size() == 1 ? details.getFirst().code() : RESOURCES_MISSING)
                : kind == Kind.PATCH ? RELATIONS_CONFLICT : RELATION_CONFLICT;
        return CatalogueHttp.json(missing ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT, new ContractError(top, details));
    }

    private static String code(Problem problem) {
        return switch (problem) {
            case FAMILY_MISSING -> FAMILY_MISSING;
            case ATTRIBUTE_MISSING -> ATTRIBUTE_MISSING;
            case ASSOCIATION_MISSING -> RELATION_MISSING;
            case ALREADY_ASSOCIATED -> ALREADY_EXISTS;
            case NOT_ASSOCIATED -> NOT_ASSOCIATED;
        };
    }

    private static String pointer(Kind kind, Issue issue) {
        if (issue.problem() == Problem.FAMILY_MISSING || kind == Kind.DELETE) return null;
        return switch (kind) {
            case CREATE -> "/" + ATTRIBUTE_ID;
            case REPLACE -> "/" + ATTRIBUTE_IDS + "/" + issue.index();
            case PATCH -> "/" + (issue.part() == Part.ADD ? ADD : REMOVE) + "/" + issue.index();
            case DELETE -> null;
        };
    }

    public record CreatedAssociation(UUID itemFamilyId, StorageAttribute storageAttribute) { }
}
