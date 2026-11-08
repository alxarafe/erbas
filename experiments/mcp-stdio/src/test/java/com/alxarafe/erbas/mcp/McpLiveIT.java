package com.alxarafe.erbas.mcp;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static java.net.HttpURLConnection.*;

/** Explicit-only integration test, against an empty disposable backend created by bin/check-mcp-spike. */
class McpLiveIT {
    private static final String USERS = "/api/users";
    private static final String LOGIN = "/api/auth/login";
    private static final String WMS_STATE = "/api/modules/erbas.wms";
    private static final String UOMS = "/api/wms/uoms";
    private static final int SAMPLES = 20;
    private static final int WARMUPS = 3;
    private final String base = Objects.requireNonNull(System.getenv("ERBAS_MCP_CORE_URL"));
    private final List<String> auditIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CoreHttp.TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Reply(int status, JsonNode body) { }
    private Reply request(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(CoreHttp.TIMEOUT)
                .header(CoreHttp.CONTENT_TYPE, CoreHttp.JSON_MEDIA_TYPE);
        if (token != null) builder.header(CoreHttp.AUTHORIZATION, "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(CoreHttp.JSON.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), CoreHttp.JSON.readTree(response.body()));
    }
    private String login(String email, String password) throws Exception {
        var response = request("POST", LOGIN, Map.of("email", email, "password", password), null);
        assertEquals(HTTP_OK, response.status());
        assertTrue(response.body().path("accessToken").isString());
        return response.body().path("accessToken").asString();
    }
    private JsonNode createUser(String admin, String email, String password, boolean privileged) throws Exception {
        var response = request("POST", USERS, Map.of("email", email, "password", password, "admin", privileged), admin);
        assertEquals(HTTP_CREATED, response.status());
        return response.body();
    }
    private JsonNode success(WireClient client) throws Exception {
        return success(client.call(Map.of()));
    }
    private JsonNode success(JsonNode response) {
        assertFalse(response.has("error"));
        var result = response.path("result");
        assertFalse(result.path("isError").asBoolean(true));
        var envelope = result.path("structuredContent");
        assertEquals(CoreHttp.TOOL, envelope.path(UomTool.CAPABILITY_ID).asString());
        assertFalse(envelope.path(UomTool.INVOCATION_ID).asString().isBlank());
        auditIds.add(envelope.path(UomTool.INVOCATION_ID).asString());
        return envelope.path(UomTool.OUTPUT);
    }
    private void denied(WireClient client, Map<String, Object> input, int httpStatus) throws Exception {
        var response = client.call(input);
        assertTrue(response.path("result").path("isError").asBoolean());
        assertEquals(httpStatus, response.path("result").path("structuredContent").path(CoreHttp.HTTP_STATUS).asInt());
        assertFalse(response.toString().contains("accessToken"));
    }
    private void metric(String name, double[] samples) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "MCP_METRIC=%s SAMPLES=%d P50_MS=%.3f P95_MS=%.3f%n",
                name, samples.length, samples[(samples.length - 1) / 2], samples[(int) Math.ceil(samples.length * .95) - 1]);
    }

    @Test void realCoreParityAuthorizationRevocationAndMeasurements() throws Exception {
        String admin = login(System.getenv("ERBAS_MCP_TEST_EMAIL"), System.getenv("ERBAS_MCP_TEST_PASSWORD"));
        assertEquals(0, request("GET", UOMS, null, admin).body().path("total").asInt());
        for (var row : List.of(Map.of("code", "MCPKG", "description", "Kilogram fixture"),
                Map.of("code", "MCPBOX", "description", "Box fixture"))) {
            assertEquals(HTTP_CREATED, request("POST", UOMS, row, admin).status());
        }
        var api = request("GET", UOMS, null, admin);
        assertEquals(HTTP_OK, api.status());
        assertEquals(2, api.body().path("total").asInt());
        var direct = request("POST", CoreHttp.INVOCATION_PATH, Map.of(CoreHttp.INPUT, Map.of()), admin);
        assertEquals(HTTP_OK, direct.status());
        assertEquals(api.body(), direct.body().path(UomTool.OUTPUT));
        double[] startup = new double[WARMUPS];
        for (int i = 0; i < startup.length; i++) {
            try (var client = new WireClient(base, admin)) { startup[i] = client.startupMillis; }
        }
        metric("process_spawn_and_initialize", startup);
        try (var client = new WireClient(base, admin)) {
            var tools = client.list().path("result").path("tools");
            assertEquals(1, tools.size());
            assertEquals(CoreHttp.TOOL, tools.get(0).path("name").asString());
            assertFalse(tools.get(0).path("inputSchema").toString().contains("$ref"));
            assertEquals(api.body(), success(client));
            assertTrue(client.request("tools/call", Map.of("name", "wms.item-families.list", "arguments", Map.of())).has("error"));
            denied(client, Map.of(UomTool.LIMIT, UomTool.CORE_MAXIMUM_LIMIT + 1), HTTP_BAD_REQUEST);
            denied(client, Map.of(UomTool.OFFSET, -1), HTTP_BAD_REQUEST);
            denied(client, Map.of("identity", "pretend-admin", "permissions", List.of("all")), HTTP_BAD_REQUEST);
            denied(client, Map.of(UomTool.LIMIT, "2"), HTTP_BAD_REQUEST);
            try {
                assertEquals(HTTP_OK, request("PATCH", WMS_STATE, Map.of("enabled", false), admin).status());
                assertEquals(0, client.list().path("result").path("tools").size());
                denied(client, Map.of(), HTTP_NOT_FOUND);
            } finally {
                assertEquals(HTTP_OK, request("PATCH", WMS_STATE, Map.of("enabled", true), admin).status());
            }
            assertEquals(1, client.list().path("result").path("tools").size());
            for (int i = 0; i < WARMUPS; i++) success(client);
            double[] mcp = new double[SAMPLES], core = new double[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                long begin = System.nanoTime();
                var directSample = request("POST", CoreHttp.INVOCATION_PATH, Map.of(CoreHttp.INPUT, Map.of()), admin);
                core[i] = (System.nanoTime() - begin) / 1_000_000d;
                assertEquals(HTTP_OK, directSample.status());
                assertEquals(api.body(), directSample.body().path(UomTool.OUTPUT));
                begin = System.nanoTime();
                var response = client.call(Map.of());
                mcp[i] = (System.nanoTime() - begin) / 1_000_000d;
                assertEquals(api.body(), success(response));
            }
            metric("direct_core_http", core);
            metric("stdio_plus_core_http", mcp);
        }
        String password = UUID.randomUUID().toString();
        String email = "mcp-unprivileged@example.test";
        var ordinary = createUser(admin, email, password, false);
        String ordinaryToken = login(email, password);
        try (var client = new WireClient(base, ordinaryToken)) {
            assertEquals(0, client.list().path("result").path("tools").size());
            denied(client, Map.of(), HTTP_NOT_FOUND);
        }
        System.out.println("MCP_AUDIT_DENIED_SUBJECT=" + ordinary.path("id").asString());
        String temporaryEmail = "mcp-revocation@example.test";
        var temporary = createUser(admin, temporaryEmail, password, true);
        String temporaryToken = login(temporaryEmail, password);
        try (var client = new WireClient(base, temporaryToken)) {
            assertEquals(1, client.list().path("result").path("tools").size());
            assertEquals(HTTP_OK, request("PATCH", USERS + "/" + temporary.path("id").asString(), Map.of("admin", false), admin).status());
            denied(client, Map.of(), HTTP_NOT_FOUND);
            assertEquals(0, client.list().path("result").path("tools").size());
            assertEquals(HTTP_OK, request("PATCH", USERS + "/" + temporary.path("id").asString(), Map.of("enabled", false), admin).status());
            denied(client, Map.of(), HTTP_UNAUTHORIZED);
            assertTrue(client.list().has("error"));
        }
        System.out.println("MCP_AUDIT_DENIED_SUBJECT=" + temporary.path("id").asString());
        try (var client = new WireClient(base, "invalid-test-token")) {
            assertTrue(client.list().has("error"));
            denied(client, Map.of(), HTTP_UNAUTHORIZED);
        }
        var after = request("GET", UOMS, null, admin);
        assertEquals(api.body(), after.body());
        auditIds.forEach(id -> System.out.println("MCP_AUDIT_COMPLETED=" + id));
        System.out.println("MCP_LIVE_RESULT=passed FIXTURES=2 ORIGINAL_BACKEND_UNTOUCHED=true");
        http.close();
    }
}
