package com.alxarafe.erbas.ai;

import com.alxarafe.erbas.ai.infrastructure.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Reference exchanges copied from erbas-contract PR 44, fixture blob
 * fcc489de8c721982b083e8c0f67227afc0a6d9b1. No runtime cross-repository dependency. */
class OpenAiCompatibleAssistantProviderTests {
    private static final String PATH = "/gateway/custom/chat/completions";
    private static final String MODEL = "fixture-model";
    private static final String TOOL_ID = "fixture.records.list";
    private static final String CALL_ID = "fixture-call-1";
    private static final String TOKEN = "synthetic-token";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorization = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private URI endpoint;
    private AiAssistantProvider provider;
    private JsonNode fixtures;
    private JsonNode profile;
    private int status;

    @BeforeEach void start() throws Exception {
        try (var resource = getClass().getResourceAsStream("/ai/provider-exchanges.json")) {
            fixtures = json.readTree(resource);
        }
        profile = fixtures.path("profiles").get(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.add(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.getResponseHeaders().set(HttpHeaders.LOCATION, endpoint.toString());
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        status = HttpStatus.OK.value();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
        provider = configured(endpoint, MODEL, TOKEN);
        reply.set(profile.path("text").path("response").toString());
    }
    @AfterEach void stop() { server.stop(0); }

    private AiAssistantProvider configured(URI uri, String model, String token) {
        return AiConfiguration.configuredProvider(environment(uri, model, token));
    }
    private MockEnvironment environment(URI uri, String model, String token) {
        return new MockEnvironment().withProperty(AiConfiguration.PROTOCOL_PROPERTY, AiConfiguration.OPENAI_COMPATIBLE)
                .withProperty(AiConfiguration.ENDPOINT_PROPERTY, uri.toString())
                .withProperty(AiConfiguration.MODEL_PROPERTY, model)
                .withProperty(AiConfiguration.CREDENTIAL_PROPERTY, token)
                .withProperty(AiConfiguration.TRUSTED_LOCAL_PROPERTY, "true")
                .withProperty(AiConfiguration.TIMEOUT_PROPERTY, Long.toString(TIMEOUT.toSeconds()));
    }
    private List<AiAssistantProvider.Tool> tools() {
        return List.of(new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, TOOL_ID,
                "Records", "Read synthetic records", fixtures.path("toolSchema")));
    }
    private JsonNode toolReply(String arguments) {
        return json.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls",
                "message", Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", CALL_ID, "type", "function", "function", Map.of("name", "read_0", "arguments", arguments))))))));
    }
    private void failsSafely() {
        assertThatThrownBy(() -> provider.respond("read", tools(), List.of()))
                .isInstanceOf(RuntimeException.class).hasMessageNotContaining(TOKEN);
    }

    @Test void directTextUsesExactEndpointModelSystemUserAndBearer() {
        assertThat(provider.respond("Hello", List.of(), List.of()))
                .isEqualTo(new AiAssistantProvider.Answer("Hello from the synthetic fixture."));
        var request = requests.getFirst();
        assertThat(request.path("model").asString()).isEqualTo(MODEL);
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(request.path("messages").get(1)).isEqualTo(json.valueToTree(Map.of("role", "user", "content", "Hello")));
        assertThat(authorization).containsExactly("Bearer " + TOKEN);
    }
    @Test void replayPreservesOriginalArgumentStringCallIdAndRealResult() {
        String arguments = "{  \"limit\" : 2 }";
        var wire = toolReply(arguments);
        reply.set(wire.toString());
        var call = (AiAssistantProvider.ToolCall) provider.respond("List", tools(), List.of());
        assertThat(call.input().path("limit").isIntegralNumber()).isTrue();
        assertThat(call.capabilityId()).isEqualTo(TOOL_ID);
        assertThat(call.callId()).isEqualTo(CALL_ID);
        assertThat(requests.getFirst().path("tools").get(0).path("function").path("parameters"))
                .isEqualTo(fixtures.path("toolSchema"));
        var output = json.valueToTree(Map.of("items", fixtures.path("records")));
        var result = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, TOOL_ID,
                output, call.input(), call.callId(), call.content(), call.assistantMessage());
        reply.set(profile.path("read").path("final").toString());
        assertThat(provider.respond("List", tools(), List.of(result))).isEqualTo(
                new AiAssistantProvider.Answer(profile.path("read").path("final").path("choices").get(0).path("message").path("content").asString()));
        var history = requests.getLast().path("messages");
        assertThat(history.size()).isEqualTo(4);
        assertThat(history.get(2)).isEqualTo(wire.path("choices").get(0).path("message"));
        assertThat(history.get(2).path("tool_calls").get(0).path("function").path("arguments").asString()).isEqualTo(arguments);
        assertThat(history.get(3).path("tool_call_id").asString()).isEqualTo(CALL_ID);
        assertThat(json.readTree(history.get(3).path("content").asString())).isEqualTo(output);
        assertThat(history.get(3).has("tool_name")).isFalse();
    }
    @Test void invalidTypedArgumentsAreNotRepairedAndFeedbackAndAllAttemptsAreReplayed() {
        reply.set(toolReply("{\"limit\":\"2\",\"extra\":true}").toString());
        var call = (AiAssistantProvider.ToolCall) provider.respond("List", tools(), List.of());
        assertThat(call.input().path("limit").isString()).isTrue();
        assertThat(call.input().path("extra").asBoolean()).isTrue();
        var feedback = json.valueToTree(Map.of("status", AiAssistantProvider.INVALID_ARGUMENTS));
        var rejected = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, TOOL_ID,
                feedback, call.input(), call.callId(), call.content(), call.assistantMessage());
        reply.set(profile.path("text").path("response").toString());
        provider.respond("List", tools(), List.of(rejected, new AiAssistantProvider.ToolResult(
                AiAssistantProvider.Kind.CAPABILITY, TOOL_ID, fixtures.path("records"))));
        var history = requests.getLast().path("messages");
        assertThat(history.size()).isEqualTo(6);
        assertThat(json.readTree(history.get(3).path("content").asString())).isEqualTo(feedback);
        assertThat(history.get(2).path("tool_calls").get(0).path("function").path("arguments").asString())
                .isEqualTo("{\"limit\":\"2\",\"extra\":true}");
    }
    @Test void preservesNumericMeaningBooleanNullAndNestedValues() {
        String input = "{\"big\":123456789012345678901234567890,\"fraction\":0.1234567890123456789,\"flag\":true,\"nil\":null,\"nested\":[1,\"2\"]}";
        reply.set(toolReply(input).toString());
        var call = (AiAssistantProvider.ToolCall) provider.respond("Read", tools(), List.of());
        assertThat(call.input().path("big").asString()).isEqualTo("123456789012345678901234567890");
        assertThat(call.input().path("fraction").decimalValue().toPlainString()).isEqualTo("0.1234567890123456789");
        assertThat(call.input().path("flag").isBoolean()).isTrue();
        assertThat(call.input().path("nil").isNull()).isTrue();
        assertThat(call.input().path("nested").get(1).isString()).isTrue();
    }
    @Test void rejectsUnknownMultipleDuplicateIdsAndMalformedFunctionCalls() {
        var valid = toolReply("{}");
        for (String name : List.of("write_0", "read_1", "https://example.test", "sql")) {
            reply.set(valid.toString().replace("read_0", name)); failsSafely();
        }
        var message = valid.path("choices").get(0).path("message").deepCopy();
        ((tools.jackson.databind.node.ArrayNode) message.path("tool_calls")).add(message.path("tool_calls").get(0).deepCopy());
        reply.set(json.valueToTree(Map.of("choices", List.of(Map.of("message", message, "finish_reason", "tool_calls")))).toString());
        failsSafely();
        for (String body : List.of(valid.toString().replace("\"type\":\"function\"", "\"type\":\"builtin\""),
                valid.toString().replace("\"id\":\"" + CALL_ID + "\"", "\"id\":42"),
                valid.toString().replace(CALL_ID, ""), toolReply("[]").toString(), toolReply("not-json").toString(),
                toolReply("{} {}").toString(), toolReply("{\"limit\":1,\"limit\":2}").toString())) {
            reply.set(body); failsSafely();
        }
        reply.set(valid.toString());
        assertThatThrownBy(() -> provider.respond("read", tools(), List.of(new AiAssistantProvider.ToolResult(
                AiAssistantProvider.Kind.CAPABILITY, TOOL_ID, json.createObjectNode(), json.createObjectNode(), CALL_ID, ""))))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsReferenceMalformedIncompleteTruncatedAndErrorBodies() {
        for (var body : profile.path("malformedResponses")) { reply.set(body.toString()); failsSafely(); }
        for (String body : List.of("not-json", "null", "[]", "{} {}", toolReply("{}").toString().replace("\"assistant\"", "\"user\""),
                toolReply("{}").toString().replace("\"content\":\"\"", "\"content\":42"),
                toolReply("{}").toString().replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"stop\""))) {
            reply.set(body); failsSafely();
        }
        reply.set("x".repeat(OpenAiCompatibleAssistantProvider.MAX_RESPONSE_BYTES + 1));
        assertThatThrownBy(() -> provider.respond("read", tools(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429, 500, 503, 302, 307})
    void httpFailureAndRedirectNeverLeakBodyOrFollow(int code) {
        status = code; reply.set("sensitive-upstream");
        assertThatThrownBy(() -> provider.respond("read", tools(), List.of()))
                .isInstanceOf(AiAssistantProvider.Unavailable.class).hasMessage(null);
        assertThat(requests).hasSize(1);
    }
    @Test void timeoutIncludesStalledBodyAndConnectionFailureIsUnavailable() {
        server.removeContext(PATH);
        server.createContext(PATH, exchange -> {
            try {
                exchange.sendResponseHeaders(HttpStatus.OK.value(), 100);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
                Thread.sleep(Duration.ofMillis(400));
            } catch (Exception ignored) { /* Synthetic disconnect during deadline cancellation. */ }
            finally { exchange.close(); }
        });
        var bounded = new OpenAiCompatibleAssistantProvider(endpoint, MODEL, Duration.ofMillis(60), TOKEN, true);
        long start = System.nanoTime();
        assertThatThrownBy(() -> bounded.respond("read", tools(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        server.stop(0);
        assertThatThrownBy(() -> provider.respond("read", tools(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
    }
    @Test void differentDestinationModelAndNoAuthRequireConfigurationOnly() throws Exception {
        assertThat(provider.respond("Hello", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Answer.class);
        var other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new AtomicReference<JsonNode>();
        var auth = new AtomicReference<String>();
        String otherPath = "/api/v42/chat/completions";
        other.createContext(otherPath, exchange -> {
            observed.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            auth.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        other.start();
        try {
            configured(URI.create("http://127.0.0.1:" + other.getAddress().getPort() + otherPath), "another-model", "")
                    .respond("Hello", List.of(), List.of());
            assertThat(observed.get().path("model").asString()).isEqualTo("another-model");
            assertThat(auth.get()).isNull();
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().path("model").asString()).isEqualTo(MODEL);
        } finally { other.stop(0); }
    }
    @Test void incorrectOperationAndCredentialFailWithoutFallback() {
        var wrongOperation = configured(URI.create(endpoint.toString().replace(PATH, "/absent-operation")), MODEL, TOKEN);
        assertThatThrownBy(() -> wrongOperation.respond("Hello", List.of(), List.of()))
                .isInstanceOf(AiAssistantProvider.Unavailable.class).hasMessage(null);
        server.removeContext(PATH);
        server.createContext(PATH, exchange -> {
            try {
                assertThat(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer incorrect-token");
                exchange.sendResponseHeaders(HttpStatus.UNAUTHORIZED.value(), -1);
            } finally { exchange.close(); }
        });
        assertThatThrownBy(() -> configured(endpoint, MODEL, "incorrect-token").respond("Hello", List.of(), List.of()))
                .isInstanceOf(AiAssistantProvider.Unavailable.class).hasMessage(null);
        assertThat(requests).isEmpty();
    }
    @Test void invalidExplicitConfigurationNeverFallsBackAndExternalRequiresTlsAndCredential() {
        var env = environment(endpoint, MODEL, TOKEN)
                .withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, endpoint.toString().replace(PATH, OllamaAssistantProvider.CHAT_PATH))
                .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL);
        for (String protocol : List.of("unknown", "groq", "openai", " ")) {
            env.withProperty(AiConfiguration.PROTOCOL_PROPERTY, protocol);
            assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(UnavailableAssistantProvider.class);
        }
        env.withProperty(AiConfiguration.PROTOCOL_PROPERTY, AiConfiguration.OPENAI_COMPATIBLE);
        for (String uri : List.of("file:///tmp/model", "http://user:password@internal/api", "https://host/api?key=secret",
                "https://host/api#fragment", "https://host", "http://host:0/api", "http://host:99999/api")) {
            env.withProperty(AiConfiguration.ENDPOINT_PROPERTY, uri);
            assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(UnavailableAssistantProvider.class);
        }
        env.withProperty(AiConfiguration.ENDPOINT_PROPERTY, endpoint.toString()).withProperty(AiConfiguration.TRUSTED_LOCAL_PROPERTY, "false");
        assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(UnavailableAssistantProvider.class);
        env.withProperty(AiConfiguration.ENDPOINT_PROPERTY, "https://provider.example/custom/completion");
        assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(OpenAiCompatibleAssistantProvider.class);
        for (String token : List.of("", "bad\r\nheader", " spaces ", "Bearer token")) {
            env.withProperty(AiConfiguration.CREDENTIAL_PROPERTY, token);
            assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(UnavailableAssistantProvider.class);
        }
        assertThat(requests).isEmpty();
    }
    @Test void explicitNativeSelectionAndLegacyConfigurationAreRetained() {
        var nativeUri = URI.create(endpoint.toString().replace(PATH, OllamaAssistantProvider.CHAT_PATH));
        var env = new MockEnvironment().withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, nativeUri.toString())
                .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL);
        assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(OllamaAssistantProvider.class);
        env.withProperty(AiConfiguration.PROTOCOL_PROPERTY, AiConfiguration.OLLAMA_NATIVE)
                .withProperty(AiConfiguration.TRUSTED_LOCAL_PROPERTY, "true");
        assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(OllamaAssistantProvider.class);
        env.withProperty(AiConfiguration.PROTOCOL_PROPERTY, AiConfiguration.OPENAI_COMPATIBLE);
        assertThat(AiConfiguration.configuredProvider(env)).isInstanceOf(UnavailableAssistantProvider.class);
    }
    @Test void knownNestedReferencesResolveAndUnknownReferencesAreNotAdvertised() {
        var known = new AiAssistantProvider.Tool(AiAssistantProvider.Kind.PRIMITIVE, TOOL_ID, "Nested", "Read",
                json.readTree("{\"type\":\"object\",\"properties\":{\"page\":{\"$ref\":\"#/components/schemas/CapabilityListInput\"}}}"));
        var unknown = new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, "fixture.hidden.list", "Unknown", "Read",
                json.readTree("{\"type\":\"object\",\"properties\":{\"page\":{\"$ref\":\"#/components/schemas/Unknown\"}}}"));
        provider.respond("Hello", List.of(known, unknown), List.of());
        var definitions = requests.getLast().path("tools");
        assertThat(definitions.size()).isEqualTo(1);
        var page = definitions.get(0).path("function").path("parameters").path("properties").path("page");
        assertThat(page.has("$ref")).isFalse();
        assertThat(page.path("properties").has("limit")).isTrue();
        reply.set(toolReply("{}").toString());
        assertThat(provider.respond("read", List.of(known), List.of())).isInstanceOf(AiAssistantProvider.PrimitiveCall.class);
    }
    @Test void invalidTimeoutModelTrustAndCredentialAreUnavailableWithoutRequests() {
        for (String value : List.of("0", "-1", "301", "1.5", "infinite", "9223372036854775808")) {
            assertThat(AiConfiguration.configuredProvider(environment(endpoint, MODEL, TOKEN)
                    .withProperty(AiConfiguration.TIMEOUT_PROPERTY, value))).isInstanceOf(UnavailableAssistantProvider.class);
        }
        assertThat(configured(endpoint, "", TOKEN)).isInstanceOf(UnavailableAssistantProvider.class);
        assertThat(AiConfiguration.configuredProvider(environment(endpoint, MODEL, TOKEN)
                .withProperty(AiConfiguration.TRUSTED_LOCAL_PROPERTY, "yes"))).isInstanceOf(UnavailableAssistantProvider.class);
        assertThat(configured(URI.create("http://inference:8000/custom/chat"), MODEL, "")).isInstanceOf(OpenAiCompatibleAssistantProvider.class);
        assertThat(requests).isEmpty();
    }
    @Test void explicitNoProxyAndInterruptionAreControlled() {
        var previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            public List<Proxy> select(URI uri) { throw new AssertionError("System proxy must not be consulted"); }
            public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
        });
        try {
            assertThat(provider.respond("Hello", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Answer.class);
        } finally { ProxySelector.setDefault(previous); }
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> provider.respond("Hello", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
