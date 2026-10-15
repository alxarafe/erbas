package com.alxarafe.erbas.wms.http;

import java.util.List;
import java.util.Map;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.modularity.http.ModuleEndpoint;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ModuleEndpoint(WmsModule.ID)
public class StorageAttributeController {
    public static final String STORAGE_ATTRIBUTES_PATH = "/api/wms/storage-attributes";
    public static final String CODE_FIELD = "code";
    public static final String NAME_FIELD = "name";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String CODE_CONFLICT = "wms_storage_attribute_code_conflict";
    private static final String OFFSET_QUERY = "offset";
    private static final String LIMIT_QUERY = "limit";
    private static final List<PagedCollection.Order> CODE_ASCENDING =
            List.of(new PagedCollection.Order(CODE_FIELD, "asc"));
    private final StorageAttributeCatalogue catalogue;
    // Same strict tree strategy as UoM, without changing that existing boundary.
    private final JsonMapper parser = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public StorageAttributeController(StorageAttributeCatalogue catalogue) { this.catalogue = catalogue; }

    @PostMapping(path = STORAGE_ATTRIBUTES_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody String body, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        final String code;
        final String name;
        try {
            var request = parser.readTree(body);
            if (request == null || !request.isObject() || request.size() != 2
                    || !request.has(CODE_FIELD) || !request.get(CODE_FIELD).isString()
                    || !request.has(NAME_FIELD) || !request.get(NAME_FIELD).isString()) {
                return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
            }
            code = request.get(CODE_FIELD).asString();
            name = request.get(NAME_FIELD).asString();
        } catch (JacksonException malformed) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        }
        var result = catalogue.create(code, name);
        return switch (result.outcome()) {
            case CREATED -> json(HttpStatus.CREATED, result.attribute());
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
            case CODE_CONFLICT -> error(HttpStatus.CONFLICT, CODE_CONFLICT);
        };
    }

    @GetMapping(path = STORAGE_ATTRIBUTES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> entry.getValue().size() != 1
                || !(entry.getKey().equals(OFFSET_QUERY) || entry.getKey().equals(LIMIT_QUERY)))) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        }
        var pagination = PagedCollection.pagination(query.getFirst(OFFSET_QUERY), query.getFirst(LIMIT_QUERY));
        if (pagination.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var applied = pagination.orElseThrow();
        var window = catalogue.list(applied.offset(), applied.limit());
        return json(HttpStatus.OK, new PagedCollection<>(window.items(), applied.offset(), applied.limit(),
                window.total(), CODE_ASCENDING));
    }

    static ResponseEntity<Object> error(HttpStatus status, String code) { return json(status, Map.of(CODE_FIELD, code)); }

    private static ResponseEntity<Object> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
