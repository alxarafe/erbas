package com.alxarafe.erbas.capabilities.http;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.capabilities.CapabilityDiscovery;
import com.alxarafe.erbas.adapter.http.ContractError;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.auth.application.UserIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** CORE discovery is deliberately not provider-module gated. */
@RestController
public class CapabilityController {
    public static final String PATH = "/api/core/capabilities";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String NOT_FOUND = "capability_not_found";
    private static final String OFFSET = "offset";
    private static final String LIMIT = "limit";
    private static final Set<String> QUERY_KEYS = Set.of(OFFSET, LIMIT);
    private final CapabilityDiscovery discovery;

    public CapabilityController(CapabilityDiscovery discovery) { this.discovery = discovery; }
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@AuthenticationPrincipal UserIdentity user, @RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !QUERY_KEYS.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        }
        var pagination = PagedCollection.pagination(query.getFirst(OFFSET), query.getFirst(LIMIT));
        if (pagination.isEmpty()) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        var applied = pagination.orElseThrow();
        var effective = discovery.effective(user);
        var items = applied.offset().compareTo(BigInteger.valueOf(effective.size())) >= 0 ? List.<Capability>of()
                : effective.stream().skip(applied.offset().longValueExact()).limit(applied.limit()).toList();
        return json(HttpStatus.OK, PagedCollection.of(items, applied, effective.size()));
    }
    @GetMapping(path = PATH + "/{capabilityId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@AuthenticationPrincipal UserIdentity user, @PathVariable String capabilityId,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty() || !Capability.validId(capabilityId)) return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        return discovery.effective(user).stream().filter(capability -> capability.id().equals(capabilityId)).findFirst()
                .map(capability -> json(HttpStatus.OK, capability)).orElseGet(() -> error(HttpStatus.NOT_FOUND, NOT_FOUND));
    }
    private static ResponseEntity<Object> error(HttpStatus status, String code) { return json(status, ContractError.of(code)); }
    private static ResponseEntity<Object> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(body);
    }
}
