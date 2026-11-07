package com.alxarafe.erbas.ai;

import com.alxarafe.erbas.ai.infrastructure.*;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OllamaAssistantProviderTests {
    private static final String UOMS = DeterministicAssistantProvider.UOMS_LIST;
    private static final String MODEL = "synthetic-test-model";
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private HttpServer server;
    private OllamaAssistantProvider provider;
    private URI endpoint;
    private int status;

    @BeforeEach void start() throws Exception {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OllamaAssistantProvider.CHAT_PATH, exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", endpoint.toString());
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + OllamaAssistantProvider.CHAT_PATH);
        provider = new OllamaAssistantProvider(endpoint, MODEL);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void incompleteOrInvalidConfigurationIsUnavailableWithoutNetwork() {
        for (var environment : List.of(new MockEnvironment(),
                new MockEnvironment().withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, endpoint.toString()),
                new MockEnvironment().withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL),
                new MockEnvironment().withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, "file:///tmp/model")
                        .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL))) {
            assertThat(OllamaAssistantProvider.configured(environment)).isInstanceOf(UnavailableAssistantProvider.class);
        }
        assertThat(requests).isEmpty();
        assertThat(OllamaAssistantProvider.configured(new MockEnvironment()
                .withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, endpoint.toString())
                .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL))).isInstanceOf(OllamaAssistantProvider.class);
    }
    @Test void timeoutDefaultsToThirtySecondsAndAcceptsExplicitValuesUpToMaximum() {
        var environment = new MockEnvironment().withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, endpoint.toString())
                .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL);
        assertThat(OllamaAssistantProvider.configured(environment)).extracting("requestTimeout")
                .isEqualTo(OllamaAssistantProvider.DEFAULT_REQUEST_TIMEOUT);
        assertThat(provider).extracting("requestTimeout").isEqualTo(OllamaAssistantProvider.DEFAULT_REQUEST_TIMEOUT);
        for (long seconds : List.of(1L, 45L, OllamaAssistantProvider.MAX_REQUEST_TIMEOUT.toSeconds())) {
            environment.withProperty(OllamaAssistantProvider.TIMEOUT_SECONDS_PROPERTY, Long.toString(seconds));
            assertThat(OllamaAssistantProvider.configured(environment)).extracting("requestTimeout").isEqualTo(Duration.ofSeconds(seconds));
        }
        assertThat(requests).isEmpty();
    }
    @Test void invalidTimeoutConfigurationKeepsProviderUnavailableWithoutNetwork() {
        var environment = new MockEnvironment().withProperty(OllamaAssistantProvider.ENDPOINT_PROPERTY, endpoint.toString())
                .withProperty(OllamaAssistantProvider.MODEL_PROPERTY, MODEL);
        for (String value : List.of("0", "-1", Long.toString(OllamaAssistantProvider.MAX_REQUEST_TIMEOUT.toSeconds() + 1),
                "", " ", "1.5", "PT30S", "infinite", "9223372036854775808")) {
            environment.withProperty(OllamaAssistantProvider.TIMEOUT_SECONDS_PROPERTY, value);
            assertThat(OllamaAssistantProvider.configured(environment)).isInstanceOf(UnavailableAssistantProvider.class);
        }
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), OllamaAssistantProvider.MAX_REQUEST_TIMEOUT.plusSeconds(1))) {
            assertThatThrownBy(() -> new OllamaAssistantProvider(endpoint, MODEL, timeout)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OllamaAssistantProvider(endpoint, MODEL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }
    @Test void configuredTimeoutBoundsTheActualHttpResponseWait() {
        server.removeContext(OllamaAssistantProvider.CHAT_PATH);
        server.createContext(OllamaAssistantProvider.CHAT_PATH, exchange -> {
            try { Thread.sleep(Duration.ofMillis(250)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var bounded = new OllamaAssistantProvider(endpoint, MODEL, Duration.ofMillis(50));
        assertThatThrownBy(() -> bounded.respond("Synthetic timeout", List.of(), List.of()))
                .isInstanceOf(AiAssistantProvider.Unavailable.class).hasMessage(null);
    }
    @Test void resolvesListSchemaWithoutChangingDescriptorAndUsesActualResults() {
        var schema = json.readTree("{\"$ref\":\"#/components/schemas/CapabilityListInput\"}");
        var tools = List.of(new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, UOMS, "Units", "Read units", schema));
        reply.set("{\"done\":true,\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"name\":\"read_0\",\"arguments\":{}}}]}}");
        var call = (AiAssistantProvider.ToolCall) provider.respond("List units", tools, List.of());
        assertThat(call.capabilityId()).isEqualTo(UOMS);
        assertThat(call.input()).isEqualTo(json.createObjectNode());
        assertThat(call.callId()).isNotBlank();
        var parameters = requests.getFirst().path("tools").get(0).path("function").path("parameters");
        assertThat(parameters.has("$ref")).isFalse();
        assertThat(parameters.path("properties").path("limit").path("maximum").asInt()).isEqualTo(PagedCollection.MAXIMUM_LIMIT);
        assertThat(parameters.has("required")).isFalse();
        assertThat(schema.has("$ref")).isTrue();
        assertThat(requests.getFirst().path("model").asString()).isEqualTo(MODEL);
        assertThat(requests.getFirst().path("stream").asBoolean()).isFalse();
        for (String value : List.of("persisted unit A", "changed persisted unit B")) {
            reply.set(json.writeValueAsString(Map.of("done", true, "message", Map.of("role", "assistant", "content", value))));
            assertThat(provider.respond("List units", tools, List.of(new AiAssistantProvider.ToolResult(
                    AiAssistantProvider.Kind.CAPABILITY, UOMS, json.valueToTree(Map.of("description", value))))))
                    .isEqualTo(new AiAssistantProvider.Answer(value));
            assertThat(requests.getLast().path("messages").get(3).path("content").asString()).contains(value);
        }
    }
    @Test void acceptsOnlyAdvertisedToolsAndStructuredArguments() {
        var tools = List.of(new AiAssistantProvider.Tool(AiAssistantProvider.Kind.PRIMITIVE, "fixture.units.read", "Read", "Read", json.readTree("{\"type\":\"object\"}")));
        reply.set("{\"done\":true,\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"name\":\"read_0\",\"arguments\":{}}}]}}");
        assertThat(provider.respond("read", tools, List.of())).isInstanceOf(AiAssistantProvider.PrimitiveCall.class);
        for (String name : List.of("https://example.test", "write_0", "read_1")) {
            reply.set(json.writeValueAsString(Map.of("done", true, "message", Map.of("role", "assistant", "tool_calls", List.of(
                    Map.of("function", Map.of("name", name, "arguments", Map.of())))))));
            assertThatThrownBy(() -> provider.respond("read", tools, List.of())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void malformedAndOversizedResponsesAndHttpErrorsFailSafely() {
        reply.set("private-provider-error"); status = 503;
        assertThatThrownBy(() -> provider.respond("read", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class)
                .hasMessage(null);
        status = 302;
        int beforeRedirect = requests.size();
        assertThatThrownBy(() -> provider.respond("read", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
        assertThat(requests).hasSize(beforeRedirect + 1);
        status = 200;
        for (String body : List.of("{}", "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":42}}", "not-json")) {
            reply.set(body);
            assertThatThrownBy(() -> provider.respond("read", List.of(), List.of())).isInstanceOf(RuntimeException.class);
        }
        reply.set("x".repeat(OllamaAssistantProvider.MAX_RESPONSE_BYTES + 1));
        assertThatThrownBy(() -> provider.respond("read", List.of(), List.of())).isInstanceOf(AiAssistantProvider.Unavailable.class);
    }
    @Test void multipleCallsAndNonObjectArgumentsCannotExecute() {
        var tool = new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, UOMS, "Units", "Read", json.readTree("{\"type\":\"object\"}"));
        var function = Map.of("function", Map.of("name", "read_0", "arguments", Map.of()));
        reply.set(json.writeValueAsString(Map.of("done", true, "message", Map.of("role", "assistant", "tool_calls", List.of(function, function)))));
        assertThatThrownBy(() -> provider.respond("read", List.of(tool), List.of())).isInstanceOf(IllegalStateException.class);
        reply.set(json.writeValueAsString(Map.of("done", true, "message", Map.of("role", "assistant", "tool_calls", List.of(
                Map.of("function", Map.of("name", "read_0", "arguments", "http://example.test")))))));
        assertThatThrownBy(() -> provider.respond("read", List.of(tool), List.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void replaysOriginalCallsArgumentsAndCorrelatedFeedbackBeforeSuccessfulResults() {
        var tools = List.of(new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, UOMS, "Units", "Read units",
                json.readTree("{\"$ref\":\"#/components/schemas/CapabilityListInput\"}")));
        reply.set("{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"Need current data\",\"tool_calls\":[{\"id\":\"model-call-1\",\"function\":{\"name\":\"read_0\",\"arguments\":{\"limit\":\"1000\"}}}]}}");
        var call = (AiAssistantProvider.ToolCall) provider.respond("List units", tools, List.of());
        assertThat(call.input().path("limit").isString()).isTrue();
        assertThat(call.callId()).isEqualTo("model-call-1");
        var feedback = json.valueToTree(Map.of("status", AiAssistantProvider.INVALID_ARGUMENTS));
        var rejected = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, UOMS, feedback,
                call.input(), call.callId(), call.content());
        var successful = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, UOMS,
                json.valueToTree(Map.of("description", "Current persisted unit")), json.createObjectNode(), "model-call-2", "Retry");
        reply.set("{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"Current persisted unit\"}}");
        assertThat(provider.respond("List units", tools, List.of(rejected, successful)))
                .isEqualTo(new AiAssistantProvider.Answer("Current persisted unit"));
        var history = requests.getLast().path("messages");
        assertThat(history.size()).isEqualTo(6);
        assertThat(history.get(1).path("content").asString()).isEqualTo("List units");
        assertThat(history.get(2).path("content").asString()).isEqualTo("Need current data");
        assertThat(history.get(2).path("tool_calls").get(0).path("function").path("arguments")).isEqualTo(call.input());
        for (int index : List.of(2, 4)) {
            assertThat(history.get(index).path("role").asString()).isEqualTo("assistant");
            assertThat(history.get(index + 1).path("role").asString()).isEqualTo("tool");
            assertThat(history.get(index).path("tool_calls").get(0).path("id"))
                    .isEqualTo(history.get(index + 1).path("tool_call_id"));
        }
        assertThat(json.readTree(history.get(3).path("content").asString())).isEqualTo(feedback);
    }
}
