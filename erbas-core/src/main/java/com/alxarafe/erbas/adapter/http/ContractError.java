package com.alxarafe.erbas.adapter.http;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.json.JsonMapper;

/** Closed v0.8.0 technical error representation, independent of any functional module. */
public record ContractError(String code, List<Detail> errors) {
    private static final JsonMapper JSON = new JsonMapper();

    public ContractError {
        if (code == null || errors == null || errors.isEmpty()) throw new IllegalArgumentException("Missing error details");
        errors = List.copyOf(errors);
    }

    public static ContractError of(String code) { return new ContractError(code, List.of(new Detail(code, null, null, null))); }
    public static String json(String code) { return JSON.writeValueAsString(of(code)); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String code, String resource, String resourceId, String path) {
        public Detail { if (code == null) throw new IllegalArgumentException("Missing detail code"); }
    }
}
