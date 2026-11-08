package com.alxarafe.erbas.mcp;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AdapterTests {
    private static final String TEST_TOKEN = "fixture-opaque-token";
    private static final String CORE_ID = "fixture-core-invocation";
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private String base;
    private volatile boolean allowed = true;
    private volatile boolean wrongSchema;
    private volatile Object received;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Object body;
            int status = HttpURLConnection.HTTP_OK;
            if (!exchange.getRequestHeaders().getFirst(CoreHttp.AUTHORIZATION).equals("Bearer " + TEST_TOKEN)) {
                status = HttpURLConnection.HTTP_UNAUTHORIZED;
                body = Map.of(CoreHttp.CODE, "unauthorized");
            } else if (!allowed) {
                status = HttpURLConnection.HTTP_NOT_FOUND;
                body = Map.of(CoreHttp.CODE, "capability_not_found");
            } else if (exchange.getRequestURI().getPath().equals(CoreHttp.CAPABILITY_PATH)) {
                calls.incrementAndGet();
                body = Map.of("id", CoreHttp.TOOL, "effect", "read", "description", "List fixture units",
                        "inputSchema", Map.of("$ref", wrongSchema ? "#/unknown" : UomTool.LIST_REFERENCE));
            } else if (exchange.getRequestURI().getPath().equals(CoreHttp.INVOCATION_PATH)) {
                calls.incrementAndGet();
                received = CoreHttp.JSON.readTree(exchange.getRequestBody().readAllBytes());
                body = Map.of(UomTool.INVOCATION_ID, CORE_ID, UomTool.CAPABILITY_ID, CoreHttp.TOOL,
                        UomTool.OUTPUT, Map.of("items", java.util.List.of(), "total", 0));
            } else {
                status = HttpURLConnection.HTTP_NOT_FOUND;
                body = Map.of(CoreHttp.CODE, "not_found");
            }
            byte[] bytes = CoreHttp.JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set(CoreHttp.CONTENT_TYPE, CoreHttp.JSON_MEDIA_TYPE);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void wireDiscoveryIsSelfContainedAndCallPreservesCoreEnvelope() throws Exception {
        var client = new WireClient(base, TEST_TOKEN);
        try (client) {
            var tools = client.list().path("result").path("tools");
            assertEquals(1, tools.size());
            assertEquals(CoreHttp.TOOL, tools.get(0).path("name").asString());
            var schema = tools.get(0).path("inputSchema");
            assertFalse(schema.toString().contains("$ref"));
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(UomTool.CORE_MAXIMUM_LIMIT, schema.path("properties").path(UomTool.LIMIT).path("maximum").asInt());
            var result = client.call(Map.of(UomTool.LIMIT, 2)).path("result");
            assertFalse(result.path("isError").asBoolean(true));
            assertEquals(CORE_ID, result.path("structuredContent").path(UomTool.INVOCATION_ID).asString());
            assertEquals(CoreHttp.JSON.readTree("{\"input\":{\"limit\":2}}"), received);
        }
        assertFalse(client.stderr().contains(TEST_TOKEN));
    }
    @Test void eachListRechecksAuthorityAndCallDoesNotTrustPreviousList() throws Exception {
        try (var client = new WireClient(base, TEST_TOKEN)) {
            assertEquals(1, client.list().path("result").path("tools").size());
            allowed = false;
            assertTrue(client.call(Map.of()).path("result").path("isError").asBoolean());
            assertEquals(0, client.list().path("result").path("tools").size());
        }
    }
    @Test void unknownToolNeverReachesCore() throws Exception {
        try (var client = new WireClient(base, TEST_TOKEN)) {
            var response = client.request("tools/call", Map.of("name", "wms.item-families.list", "arguments", Map.of()));
            assertTrue(response.has("error"));
            assertEquals(0, calls.get());
        }
    }
    @Test void badCredentialNeverPublishesTool() throws Exception {
        try (var client = new WireClient(base, "invalid-fixture-token")) {
            assertTrue(client.list().has("error"));
            assertEquals(0, calls.get());
        }
    }
    @Test void unsupportedReferenceFailsClosed() throws Exception {
        wrongSchema = true;
        try (var client = new WireClient(base, TEST_TOKEN)) { assertTrue(client.list().has("error")); }
    }
    @Test void malformedToolRequestDoesNotReachCore() throws Exception {
        try (var client = new WireClient(base, TEST_TOKEN)) {
            assertTrue(client.request("tools/call", Map.of("name", CoreHttp.TOOL, "arguments", "not-an-object")).has("error"));
            assertEquals(0, calls.get());
        }
    }
    @Test void identityAndPermissionFieldsAreNeverTakenAsAuthority() throws Exception {
        try (var client = new WireClient(base, TEST_TOKEN)) {
            client.call(Map.of("identity", "pretend-admin", "permissions", java.util.List.of("all")));
            var body = (tools.jackson.databind.JsonNode) received;
            assertEquals(1, body.size());
            assertTrue(body.path(CoreHttp.INPUT).has("identity"));
        }
    }
    @Test void configurationRejectsCredentialsInUrlAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new CoreHttp("https://user:password@example.test", TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new CoreHttp("https://example.test?token=value", TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new CoreHttp(base, "token\nheader"));
        assertThrows(IllegalArgumentException.class, () -> new CoreHttp(base, ""));
    }
}
