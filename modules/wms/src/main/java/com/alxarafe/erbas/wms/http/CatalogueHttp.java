package com.alxarafe.erbas.wms.http;

import java.util.List;
import java.util.Optional;

import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.adapter.http.ContractError;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict request and response mechanics shared by the WMS catalogue adapters. */
final class CatalogueHttp {
    static final String CODE_FIELD = "code";
    static final String INVALID_REQUEST = "invalid_request";
    static final List<PagedCollection.Order> CODE_ASCENDING =
            List.of(new PagedCollection.Order(CODE_FIELD, "asc"));
    private static final String OFFSET_QUERY = "offset";
    private static final String LIMIT_QUERY = "limit";
    // Same strict tree strategy as Users, without changing that existing boundary.
    private static final JsonMapper PARSER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private CatalogueHttp() { }

    static Optional<CreateInput> input(String body, String textField) {
        var object = object(body);
        if (object.isEmpty()) return Optional.empty();
        var request = object.orElseThrow();
        if (request.size() != 2
                || !request.has(CODE_FIELD) || !request.get(CODE_FIELD).isString()
                || !request.has(textField) || !request.get(textField).isString()) return Optional.empty();
        return Optional.of(new CreateInput(request.get(CODE_FIELD).asString(), request.get(textField).asString()));
    }

    static Optional<JsonNode> object(String body) {
        try {
            var request = PARSER.readTree(body);
            return request != null && request.isObject() ? Optional.of(request) : Optional.empty();
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    static Optional<PagedCollection.Pagination> pagination(MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> entry.getValue().size() != 1
                || !(entry.getKey().equals(OFFSET_QUERY) || entry.getKey().equals(LIMIT_QUERY)))) {
            return Optional.empty();
        }
        return PagedCollection.pagination(query.getFirst(OFFSET_QUERY), query.getFirst(LIMIT_QUERY));
    }

    static ResponseEntity<Object> error(HttpStatus status, String code) { return json(status, ContractError.of(code)); }

    static ResponseEntity<Object> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }

    record CreateInput(String code, String text) { }
}
