package com.alxarafe.erbas.modularity.http;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.modularity.ModuleDescriptor;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleUnavailableException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** CORE administration: deliberately not annotated with ModuleEndpoint. */
@RestController
public class ModuleController {
    public static final String MODULES_PATH = "/api/modules";
    public static final String MODULE_PATH = MODULES_PATH + "/{id}";
    public static final String ENABLED_FIELD = "enabled";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String NOT_FOUND = "module_not_found";
    public static final String DEPENDENCY_CONFLICT = "module_dependency_conflict";
    private final ModuleRegistry registry;
    private final ModuleStateService states;
    private final JsonMapper parser = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ModuleController(ModuleRegistry registry, ModuleStateService states) {
        this.registry = registry;
        this.states = states;
    }

    public record Module(String id, int moduleApiVersion, List<String> dependencies, boolean enabled) { }

    @GetMapping(path = MODULES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@RequestParam(required = false) String offset,
                                  @RequestParam(required = false) String limit) {
        var pagination = PagedCollection.pagination(offset, limit);
        if (pagination.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var applied = pagination.orElseThrow();
        var available = registry.modules(); // Registry already owns case-sensitive id ASC ordering.
        var items = applied.offset().compareTo(BigInteger.valueOf(available.size())) >= 0
                ? List.<Module>of() : available.stream().skip(applied.offset().longValueExact())
                    .limit(applied.limit()).map(module -> representation(module.descriptor())).toList();
        return json(HttpStatus.OK, PagedCollection.of(items, applied, available.size()));
    }

    @PatchMapping(path = MODULE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody String body) {
        boolean enabled;
        try {
            var request = parser.readTree(body);
            if (request == null || !request.isObject() || request.size() != 1
                    || !request.has(ENABLED_FIELD) || !request.get(ENABLED_FIELD).isBoolean()) {
                return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
            }
            enabled = request.get(ENABLED_FIELD).asBoolean();
        } catch (JacksonException malformed) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        }
        var descriptor = registry.modules().stream().map(module -> module.descriptor())
                .filter(module -> module.id().equals(id)).findFirst()
                .orElseThrow(() -> new ModuleUnavailableException(id));
        if (enabled) states.enable(id);
        else states.disable(id);
        return json(HttpStatus.OK, representation(descriptor));
    }

    private Module representation(ModuleDescriptor descriptor) {
        return new Module(descriptor.id(), descriptor.moduleApiVersion(), descriptor.dependencies().stream().sorted().toList(),
                states.isEnabled(descriptor.id()));
    }

    static ResponseEntity<Object> error(HttpStatus status, String code) { return json(status, Map.of("code", code)); }

    private static ResponseEntity<Object> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
