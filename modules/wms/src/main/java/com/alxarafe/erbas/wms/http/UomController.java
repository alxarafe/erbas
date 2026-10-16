package com.alxarafe.erbas.wms.http;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.modularity.http.ModuleEndpoint;
import com.alxarafe.erbas.wms.WmsModule;
import com.alxarafe.erbas.wms.application.UomCatalogue;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@ModuleEndpoint(WmsModule.ID)
public class UomController {
    public static final String UOMS_PATH = "/api/wms/uoms";
    public static final String CODE_FIELD = CatalogueHttp.CODE_FIELD;
    public static final String DESCRIPTION_FIELD = "description";
    public static final String INVALID_REQUEST = CatalogueHttp.INVALID_REQUEST;
    public static final String CODE_CONFLICT = "wms_uom_code_conflict";
    private final UomCatalogue catalogue;

    public UomController(UomCatalogue catalogue) { this.catalogue = catalogue; }

    @PostMapping(path = UOMS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody String body, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var input = CatalogueHttp.input(body, DESCRIPTION_FIELD);
        if (input.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var value = input.orElseThrow();
        var result = catalogue.create(value.code(), value.text());
        return switch (result.outcome()) {
            case CREATED -> json(HttpStatus.CREATED, result.uom());
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
            case CODE_CONFLICT -> error(HttpStatus.CONFLICT, CODE_CONFLICT);
        };
    }

    @GetMapping(path = UOMS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@RequestParam MultiValueMap<String, String> query) {
        var pagination = CatalogueHttp.pagination(query);
        if (pagination.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var applied = pagination.orElseThrow();
        var window = catalogue.list(applied.offset(), applied.limit());
        return json(HttpStatus.OK, new PagedCollection<>(window.items(), applied.offset(), applied.limit(),
                window.total(), CatalogueHttp.CODE_ASCENDING));
    }

    static ResponseEntity<Object> error(HttpStatus status, String code) { return CatalogueHttp.error(status, code); }

    private static ResponseEntity<Object> json(HttpStatus status, Object body) { return CatalogueHttp.json(status, body); }
}
