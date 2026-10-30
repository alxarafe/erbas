package com.alxarafe.erbas.ai;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.alxarafe.erbas.ai.http.AssistantAuthenticationAuditFilter;
import com.alxarafe.erbas.ai.http.AssistantController;
import com.alxarafe.erbas.ai.infrastructure.UnavailableAssistantProvider;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.*;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AssistantInvocationTests {
    private static final String OWNER = "example.inventory";
    private static final String FIRST = "example.inventory.first";
    private static final String SECOND = "example.inventory.second";
    private static final String WRITE = "example.inventory.change";
    private static final String READ_PERMISSION = "example.inventory.read";
    private static final String BODY = "{\"message\":\"private-user-message\"}";
    private static final String PRIVATE_DATA = "private-tool-data";
    private static final UserIdentity ADMIN = new UserIdentity(7, "private@example.test", true, true);
    private static final UserIdentity NORMAL = new UserIdentity(8, "normal@example.test", true, false);
    private final JsonMapper json = new JsonMapper();
    private final Map<String, Boolean> persisted = new HashMap<>();
    private final List<AiAssistantAuditSink.Entry> aiAudit = new ArrayList<>();
    private final List<CapabilityInvocationAuditSink.Entry> coreAudit = new ArrayList<>();
    private final Set<String> grants = new HashSet<>(Set.of(READ_PERMISSION));
    private final AtomicReference<UserIdentity> current = new AtomicReference<>(ADMIN);
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new AiModule(), new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(OWNER, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persisted.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persisted); }
    });
    private final CapabilityRegistry registry = new CapabilityRegistry(List.of(() -> List.of(
            descriptor(FIRST, Capability.READ), descriptor(SECOND, Capability.READ), descriptor(WRITE, Capability.WRITE))),
            modules, new CapabilitySchemaCatalog(Set.of("Input", "Output")));
    private final CapabilityDiscovery discovery = new CapabilityDiscovery(registry, states,
            List.of((user, permission) -> user.admin() && grants.contains(permission)));
    private final Handler first = new Handler(FIRST);
    private final Handler second = new Handler(SECOND);
    private final CapabilityInvocation core = new CapabilityInvocation(discovery,
            new CapabilityInvocationRegistry(registry, List.of(first, second)), coreAudit::add);

    private AssistantInvocation assistant(AiAssistantProvider provider) { return assistant(provider, user -> user.admin()); }
    private AssistantInvocation assistant(AiAssistantProvider provider, AiAssistantPermission permission) {
        states.synchronize();
        return new AssistantInvocation(permission, states, discovery, core, user -> current.get(), provider, aiAudit::add);
    }
    @Test void moduleIsNormallyRegisteredAndCanDisableAndRestore() {
        var module = new AiModule();
        assertThat(module.descriptor()).isEqualTo(new ModuleDescriptor(AiModule.ID, ModuleApi.CURRENT_VERSION, List.of()));
        var assistant = assistant((message, tools, results) -> new AiAssistantProvider.Answer("fixture answer"));
        assertThat(states.isEnabled(AiModule.ID)).isTrue();
        states.disable(AiModule.ID);
        assertThat(assistant.invoke(ADMIN, () -> BODY).errorCode()).isEqualTo(AssistantInvocation.MODULE_DISABLED);
        states.enable(AiModule.ID);
        assertThat(assistant.invoke(ADMIN, () -> BODY).errorCode()).isNull();
    }
    @Test void permissionAndModuleGatePrecedeBodyAndAuditDenialsWithoutPayloads() {
        var assistant = assistant((message, tools, results) -> { throw new AssertionError("provider called"); });
        assertThat(assistant.invoke(null, () -> { throw new AssertionError("body read"); }).errorCode())
                .isEqualTo(AssistantInvocation.UNAUTHORIZED);
        assertThat(aiAudit.getLast().subject()).isNull();
        states.disable(AiModule.ID);
        assertThat(assistant.invoke(NORMAL, () -> { throw new AssertionError("body read"); }).errorCode())
                .isEqualTo(AssistantInvocation.FORBIDDEN);
        assertThat(assistant.invoke(ADMIN, () -> { throw new AssertionError("body read"); }).errorCode())
                .isEqualTo(AssistantInvocation.MODULE_DISABLED);
        states.enable(AiModule.ID);
        assertThat(assistant.invoke(ADMIN, () -> "{").errorCode()).isEqualTo(AssistantInvocation.INVALID_REQUEST);
        assertThat(aiAudit).allSatisfy(entry -> {
            assertThat(entry.invocationId()).isNotBlank();
            assertThat(entry.outcome()).isEqualTo(AiAssistantAuditSink.Outcome.denied);
            assertThat(entry.toolsUsed()).isEmpty();
            assertThat(entry.correlationId()).isNull();
            assertThat(entry.toString()).doesNotContain(BODY, ADMIN.email());
        });
    }
    @Test void unavailableAiModuleUsesSameSafeModuleDisabledError() {
        var empty = new ModuleStateService(new ModuleRegistry(List.of()), new ModuleStateStore() {
            public Optional<Boolean> find(String id) { throw new AssertionError("unavailable module state read"); }
            public void update(Consumer<Map<String, Boolean>> change) { throw new AssertionError(); }
        });
        var assistant = new AssistantInvocation(user -> true, empty, discovery, core, user -> user,
                new UnavailableAssistantProvider(), aiAudit::add);
        assertThat(assistant.invoke(ADMIN, () -> { throw new AssertionError("body read"); }).errorCode())
                .isEqualTo(AssistantInvocation.MODULE_DISABLED);
    }
    @Test void directAnswerHasEmptyProvenanceAndOnlyEffectiveReadToolsAreExposed() {
        var assistant = assistant((message, tools, results) -> {
            assertThat(tools).extracting(Capability::id).containsExactly(FIRST, SECOND);
            assertThat(results).isEmpty();
            return new AiAssistantProvider.Answer("fixture answer");
        });
        var response = assistant.invoke(ADMIN, () -> BODY);
        assertThat(response.errorCode()).isNull();
        assertThat(response.envelope().toolsUsed()).isEmpty();
        assertThat(response.envelope().invocationId()).isNotBlank();
        assertThat(coreAudit).isEmpty();
        assertThat(aiAudit.getLast().outcome()).isEqualTo(AiAssistantAuditSink.Outcome.completed);
    }
    @Test void aiOnlyPermissionDoesNotGrantBusinessAndDisabledModulesAreFiltered() {
        var assistant = assistant((message, tools, results) -> {
            assertThat(tools).isEmpty();
            return new AiAssistantProvider.Answer("Cannot obtain current state.");
        }, user -> true);
        current.set(NORMAL);
        assertThat(assistant.invoke(NORMAL, () -> BODY).errorCode()).isNull();
        assertThat(discovery.effective(NORMAL)).isEmpty();
        states.disable(OWNER);
        assertThat(assistant.invoke(ADMIN, () -> BODY).errorCode()).isNull();
        states.enable(OWNER);
        assertThat(discovery.effective(ADMIN)).hasSize(3);
        assertThat(coreAudit).isEmpty();
    }
    @Test void multipleCallsUseRealCoreInOrderAndReturnItsActualDataToProvider() {
        var assistant = assistant((message, tools, results) -> {
            if (results.isEmpty()) return new AiAssistantProvider.ToolCall(SECOND, json.createObjectNode());
            if (results.size() == 1) return new AiAssistantProvider.ToolCall(FIRST, json.createObjectNode());
            assertThat(results).extracting(AiAssistantProvider.ToolResult::capabilityId).containsExactly(SECOND, FIRST);
            assertThat(results.getFirst().output().get("value").asString()).isEqualTo(PRIVATE_DATA);
            return new AiAssistantProvider.Answer("Answer based on actual tool results.");
        });
        var result = assistant.invoke(ADMIN, () -> BODY);
        assertThat(result.errorCode()).isNull();
        var used = result.envelope().toolsUsed();
        assertThat(used).extracting(AiAssistantAuditSink.ToolUsage::capabilityId).containsExactly(SECOND, FIRST);
        assertThat(used).extracting(AiAssistantAuditSink.ToolUsage::invocationId).doesNotHaveDuplicates()
                .doesNotContain(result.envelope().invocationId());
        assertThat(used).extracting(AiAssistantAuditSink.ToolUsage::invocationId)
                .containsExactlyElementsOf(coreAudit.stream().map(CapabilityInvocationAuditSink.Entry::invocationId).toList());
        assertThat(aiAudit.getLast().toolsUsed()).isEqualTo(used);
        assertThat(aiAudit.getLast().timestamp()).isNotNull();
        assertThat(aiAudit.getLast().duration().isNegative()).isFalse();
        assertThat(aiAudit.getLast().toString()).doesNotContain(PRIVATE_DATA, "private-user-message", ADMIN.email());
    }
    @ParameterizedTest @ValueSource(strings = {"module", "permission", "demotion", "disabled-user"})
    void changedAuthorityAfterCatalogueIsRejectedByCore(String change) {
        var assistant = assistant((message, tools, results) -> {
            assertThat(tools).extracting(Capability::id).contains(FIRST);
            switch (change) {
                case "module" -> states.disable(OWNER);
                case "permission" -> grants.clear();
                case "demotion" -> current.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
                case "disabled-user" -> current.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), false, true));
                default -> throw new AssertionError();
            }
            return new AiAssistantProvider.ToolCall(FIRST, json.createObjectNode());
        });
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(first.executions).isZero();
        assertThat(coreAudit).hasSize(1);
        assertThat(coreAudit.getFirst().outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.denied);
        assertThat(aiAudit.getLast().toolsUsed()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"unknown.inventory.list", "INVALID", WRITE, "https://example.test", ""})
    void untrustedToolIdsNeverBypassCore(String id) {
        var assistant = assistant((message, tools, results) -> new AiAssistantProvider.ToolCall(id, json.createObjectNode()));
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(coreAudit).hasSize(1);
        assertThat(first.executions + second.executions).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "1", "\"input\"", "{\"permissions\":[]}", "{\"limit\":0}"})
    void untrustedToolInputIsValidatedByCore(String input) {
        var assistant = assistant((message, tools, results) -> new AiAssistantProvider.ToolCall(FIRST, json.readTree(input)));
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(coreAudit.getLast().errorCode()).isEqualTo(CapabilityInvocation.INVALID_REQUEST);
        assertThat(first.executions).isZero();
    }
    @Test void finiteBudgetStopsRepeatedToolsAndAuditsOnlySuccessfulCoreIds() {
        var assistant = assistant((message, tools, results) -> new AiAssistantProvider.ToolCall(FIRST, json.createObjectNode()));
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(first.executions).isEqualTo(AssistantInvocation.MAX_TOOL_CALLS);
        assertThat(coreAudit).hasSize(AssistantInvocation.MAX_TOOL_CALLS);
        assertThat(aiAudit.getLast().toolsUsed()).hasSize(AssistantInvocation.MAX_TOOL_CALLS);
    }
    @Test void successfulToolBeforeFailureIsAuditedButNoFailurePayloadIsPublished() {
        var assistant = assistant((message, tools, results) -> {
            if (results.isEmpty()) return new AiAssistantProvider.ToolCall(FIRST, json.createObjectNode());
            return new AiAssistantProvider.ToolCall("unknown.inventory.list", json.createObjectNode());
        });
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(aiAudit.getLast().toolsUsed()).hasSize(1);
        assertThat(coreAudit).extracting(CapabilityInvocationAuditSink.Entry::outcome)
                .containsExactly(CapabilityInvocationAuditSink.Outcome.completed, CapabilityInvocationAuditSink.Outcome.denied);
    }
    @Test void providerExceptionNullTurnAndInvalidAnswerFailSafely() {
        for (AiAssistantProvider provider : List.<AiAssistantProvider>of(
                (message, tools, results) -> { throw new IllegalStateException("private-provider-stack-sql"); },
                (message, tools, results) -> null,
                (message, tools, results) -> new AiAssistantProvider.Answer(null),
                (message, tools, results) -> new AiAssistantProvider.Answer(""),
                (message, tools, results) -> new AiAssistantProvider.Answer(" "),
                (message, tools, results) -> new AiAssistantProvider.Answer("\0"),
                (message, tools, results) -> new AiAssistantProvider.Answer("\ud800"))) {
            assertSafeFailure(assistant(provider).invoke(ADMIN, () -> BODY));
        }
    }
    @Test void coreProviderExceptionAndInvalidOutputBecomeSameAiFailure() {
        var assistant = assistant((message, tools, results) -> new AiAssistantProvider.ToolCall(FIRST, json.createObjectNode()));
        first.failure = true;
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        first.failure = false; first.valid = false;
        assertSafeFailure(assistant.invoke(ADMIN, () -> BODY));
        assertThat(coreAudit).allSatisfy(entry -> assertThat(entry.errorCode()).isEqualTo(CapabilityInvocation.FAILED));
        assertThat(aiAudit).allSatisfy(entry -> assertThat(entry.toolsUsed()).isEmpty());
    }
    @Test void defaultProviderUnavailableAndAllHttpErrorsHaveExactClosedEnvelopes() {
        var assistant = assistant(new UnavailableAssistantProvider());
        var controller = new AssistantController(assistant);
        var response = controller.invoke(ADMIN, request(BODY));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json.<JsonNode>valueToTree(response.getBody())).isEqualTo(json.readTree(
                "{\"code\":\"assistant_unavailable\",\"errors\":[{\"code\":\"assistant_unavailable\"}]}"));
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        var invalid = controller.invoke(ADMIN, request("{"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var failure = new AssistantController(assistant((message, tools, results) -> null)).invoke(ADMIN, request(BODY));
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json.<JsonNode>valueToTree(failure.getBody())).isEqualTo(json.readTree(
                "{\"code\":\"assistant_execution_failed\",\"errors\":[{\"code\":\"assistant_execution_failed\"}]}"));
    }
    @Test void filterAuditsAuthenticationRejectionWithoutInspectingRequestPayload() throws Exception {
        var assistant = assistant(new UnavailableAssistantProvider());
        var filter = new AssistantAuthenticationAuditFilter(assistant);
        var request = request("private-bearer-or-body");
        request.setServletPath(AssistantController.PATH); request.setMethod("POST");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> ((MockHttpServletResponse) outgoing).setStatus(HttpStatus.UNAUTHORIZED.value()));
        assertThat(aiAudit).hasSize(1);
        assertThat(aiAudit.getFirst().errorCode()).isEqualTo(AssistantInvocation.UNAUTHORIZED);
        assertThat(aiAudit.getFirst().subject()).isNull();
        assertThat(aiAudit.getFirst().toString()).doesNotContain("private-bearer-or-body");
    }
    private void assertSafeFailure(AssistantInvocation.Result result) {
        assertThat(result.errorCode()).isEqualTo(AssistantInvocation.FAILED);
        assertThat(result.envelope()).isNull();
        assertThat(aiAudit.getLast().outcome()).isEqualTo(AiAssistantAuditSink.Outcome.failed);
        assertThat(aiAudit.getLast().toString()).doesNotContain(BODY, PRIVATE_DATA, "private-provider-stack-sql");
    }
    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
    private Capability descriptor(String id, String effect) {
        return new Capability(id, OWNER, "1.0.0", "Synthetic read", "Read fixture data.", effect,
                new Capability.SchemaReference(Capability.SchemaReference.PREFIX + "Input"),
                new Capability.SchemaReference(Capability.SchemaReference.PREFIX + "Output"), List.of(READ_PERMISSION));
    }
    private static final class Handler implements CapabilityInvocationHandler {
        final String id;
        int executions;
        boolean failure;
        boolean valid = true;
        Handler(String id) { this.id = id; }
        public String capabilityId() { return id; }
        public String module() { return OWNER; }
        public Object validateInput(JsonNode input) { return CapabilityListInput.parse(input); }
        public Object execute(CapabilityInvocationContext context, Object input) {
            executions++;
            if (failure) throw new IllegalStateException("private-provider-stack-sql");
            return Map.of("value", PRIVATE_DATA);
        }
        public boolean validOutput(Object output, Object input) { return valid; }
    }
}
