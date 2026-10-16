package com.alxarafe.erbas.adapter.http;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    public record HealthResponse(String status) {
    }
}
