package com.alxarafe.erbas.ai;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.*;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.read.*;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ControlledReadToolsTests {
    private static final String OWNER = "fixture.stock";
    private static final String CAPABILITY = "fixture.stock.summary";
    private static final String PRIMITIVE = "fixture.stock.list";
    private static final String BUSINESS_PERMISSION = "fixture.stock.read";
    private static final String PRIVATE_RESULT = "private-current-stock";
    private static final UserIdentity ADMIN = new UserIdentity(7, "private@example.test", true, true);
    private static final String BODY = "{\"message\":\"private-message\"}";
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<UserIdentity> persistedUser = new AtomicReference<>(ADMIN);
    private final Map<String, Boolean> persistedModules = new HashMap<>();
    private final List<AiAssistantAuditSink.Entry> aiAudit = new ArrayList<>();
    private final List<ReadPrimitiveAuditSink.Entry> primitiveAudit = new ArrayList<>();
    private final List<CapabilityInvocationAuditSink.Entry> capabilityAudit = new ArrayList<>();
    private final List<String> executions = new ArrayList<>();
    private boolean granted = true;
    private boolean primitiveFailure;
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new AiModule(), new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(OWNER, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persistedModules.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persistedModules); }
    });
    private final CapabilityPermissionPolicy permission = (user, requested) -> user.admin() && granted && BUSINESS_PERMISSION.equals(requested);
    private final CapabilityRegistry declarations = new CapabilityRegistry(List.of(() -> List.of(new Capability(CAPABILITY,
            OWNER, "1.0.0", "Stock summary", "Read fixture stock", Capability.READ,
            new Capability.SchemaReference(Capability.SchemaReference.PREFIX + "Input"),
            new Capability.SchemaReference(Capability.SchemaReference.PREFIX + "Output"), List.of(BUSINESS_PERMISSION)))),
            modules, new CapabilitySchemaCatalog(Set.of("Input", "Output")));
    private final CapabilityDiscovery discovery = new CapabilityDiscovery(declarations, states, List.of(permission));
    private final CapabilityInvocation core = new CapabilityInvocation(discovery,
            new CapabilityInvocationRegistry(declarations, List.of(new CapabilityInvocationHandler() {
                public String capabilityId() { return CAPABILITY; }
                public String module() { return OWNER; }
                public Object validateInput(JsonNode input) { return CapabilityListInput.parse(input); }
                public Object execute(CapabilityInvocationContext context, Object input) {
                    executions.add(CAPABILITY); return Map.of("value", PRIVATE_RESULT);
                }
                public boolean validOutput(Object output, Object input) { return true; }
            })), capabilityAudit::add);
    private final ReadPrimitiveRouter primitives = new ReadPrimitiveRouter(new ReadPrimitiveRegistry(List.of(new ReadPrimitiveExecutor() {
        public ReadPrimitive descriptor() {
            return new ReadPrimitive(PRIMITIVE, OWNER, "Stock entries", "Read current fixture entries", Capability.READ,
                    json.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"), List.of(BUSINESS_PERMISSION));
        }
        public Object validateInput(JsonNode input) {
            if (input == null || !input.isObject() || !input.isEmpty()) throw new InvalidInput();
            return input;
        }
        public Object execute(Context context, Object input) {
            if (primitiveFailure) throw new IllegalStateException("private-executor-secret");
            executions.add(PRIMITIVE); return Map.of("value", PRIVATE_RESULT);
        }
        public boolean validOutput(Object output, Object input) { return true; }
    }), modules), states, id -> Optional.ofNullable(persistedUser.get()), List.<FunctionalPermissionPolicy>of(permission), primitiveAudit::add);

    private AssistantInvocation assistant(AiAssistantProvider provider) {
        states.synchronize();
        return new AssistantInvocation(user -> true, states, discovery, core, primitives,
                user -> persistedUser.get(), provider, aiAudit::add);
    }
    private AiAssistantProvider.Turn call(String id) {
        return CAPABILITY.equals(id) ? new AiAssistantProvider.ToolCall(id, json.createObjectNode())
                : new AiAssistantProvider.PrimitiveCall(id, json.createObjectNode());
    }
    @Test void mixedAndRepeatedExecutionsPreserveActualOrderClosedVariantsAndUniqueIds() {
        var sequence = List.of(PRIMITIVE, CAPABILITY, PRIMITIVE);
        var service = assistant((message, tools, results) -> {
            assertThat(tools).extracting(AiAssistantProvider.Tool::kind)
                    .containsExactly(AiAssistantProvider.Kind.CAPABILITY, AiAssistantProvider.Kind.PRIMITIVE);
            assertThat(json.writeValueAsString(tools)).doesNotContain(BUSINESS_PERMISSION, OWNER + "\"", "executor", "module", "requiredPermissions");
            if (results.size() < sequence.size()) return call(sequence.get(results.size()));
            assertThat(results).extracting(AiAssistantProvider.ToolResult::id).containsExactlyElementsOf(sequence);
            assertThat(results).allSatisfy(result -> assertThat(result.output().get("value").asString()).isEqualTo(PRIVATE_RESULT));
            return new AiAssistantProvider.Answer("Grounded answer.");
        });
        var result = service.invoke(ADMIN, () -> BODY);
        assertThat(result.errorCode()).isNull();
        assertThat(executions).containsExactlyElementsOf(sequence);
        var used = result.envelope().toolsUsed();
        assertThat(used).extracting(AiAssistantAuditSink.ToolUsage::invocationId).doesNotHaveDuplicates()
                .doesNotContain(result.envelope().invocationId());
        var wire = json.<JsonNode>valueToTree(result.envelope()).get("toolsUsed");
        for (int index = 0; index < sequence.size(); index++) {
            String field = CAPABILITY.equals(sequence.get(index)) ? "capabilityId" : "primitiveId";
            assertThat(wire.get(index)).isEqualTo(json.valueToTree(Map.of(field, sequence.get(index),
                    "invocationId", used.get(index).invocationId())));
        }
        assertThat(primitiveAudit).extracting(entry -> entry.origin().sequence()).containsExactly(1, 3);
        assertThat(primitiveAudit).allSatisfy(entry -> {
            assertThat(entry.origin().parentInvocationId()).isEqualTo(result.envelope().invocationId());
            assertThat(entry.origin().correlationId()).isNull();
            assertThat(entry.toString()).doesNotContain(PRIVATE_RESULT, "private-message", ADMIN.email());
        });
        assertThat(capabilityAudit).hasSize(1);
        assertThat(aiAudit.getLast().toolsUsed()).isEqualTo(used);
    }
    @Test void primitiveOnlyResponseHasNoCapabilityOrDiscriminatorFields() {
        var service = assistant((message, tools, results) -> results.isEmpty() ? call(PRIMITIVE) : new AiAssistantProvider.Answer("Grounded."));
        var response = service.invoke(ADMIN, () -> BODY);
        assertThat(response.errorCode()).isNull();
        var used = response.envelope().toolsUsed().getFirst();
        assertThat(json.<JsonNode>valueToTree(used)).isEqualTo(json.valueToTree(Map.of("primitiveId", PRIMITIVE,
                "invocationId", used.invocationId())));
    }
    @Test void aiInvokePermissionAloneCannotGrantEitherBusinessTool() {
        persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
        var response = assistant((message, tools, results) -> {
            assertThat(tools).isEmpty(); return new AiAssistantProvider.Answer("Cannot obtain current stock.");
        }).invoke(ADMIN, () -> BODY);
        assertThat(response.errorCode()).isNull();
        assertThat(response.envelope().toolsUsed()).isEmpty();
        assertThat(executions).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"permission", "demotion", "disabled", "module"})
    void primitiveAuthorityIsReevaluatedAfterProviderSeesCatalogue(String mutation) {
        var service = assistant((message, tools, results) -> {
            assertThat(tools).extracting(AiAssistantProvider.Tool::id).contains(PRIMITIVE);
            switch (mutation) {
                case "permission" -> granted = false;
                case "demotion" -> persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
                case "disabled" -> persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), false, true));
                case "module" -> states.disable(OWNER);
                default -> throw new AssertionError();
            }
            return call(PRIMITIVE);
        });
        assertFailure(service.invoke(ADMIN, () -> BODY));
        assertThat(primitiveAudit.getLast().outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.denied);
        assertThat(executions).isEmpty();
        assertThat(aiAudit.getLast().toolsUsed()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"https://example.test/path", "/api/wms/uoms", "fixture.unknown.list"})
    void arbitraryUrlOrUnregisteredIdCannotSelectExecutor(String requested) {
        var service = assistant((message, tools, results) -> call(requested));
        assertFailure(service.invoke(ADMIN, () -> BODY));
        assertThat(executions).isEmpty();
        assertThat(aiAudit.getLast().toolsUsed()).isEmpty();
    }
    @Test void failedPrimitiveIsAbsentButEarlierSuccessfulCapabilityRemainsInAudit() {
        primitiveFailure = true;
        var service = assistant((message, tools, results) -> results.isEmpty() ? call(CAPABILITY) : call(PRIMITIVE));
        assertFailure(service.invoke(ADMIN, () -> BODY));
        assertThat(aiAudit.getLast().toolsUsed()).hasSize(1).first().isInstanceOf(AiAssistantAuditSink.CapabilityUsage.class);
        assertThat(primitiveAudit.getLast().outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.failed);
        assertThat(aiAudit.getLast().toString()).doesNotContain("private-executor-secret", PRIVATE_RESULT, "private-message");
    }
    @Test void oneGlobalBudgetIncludesCapabilitiesAndPrimitives() {
        var service = assistant((message, tools, results) -> call(results.size() % 2 == 0 ? PRIMITIVE : CAPABILITY));
        assertFailure(service.invoke(ADMIN, () -> BODY));
        assertThat(executions).hasSize(AssistantInvocation.MAX_TOOL_CALLS);
        assertThat(primitiveAudit.size() + capabilityAudit.size()).isEqualTo(AssistantInvocation.MAX_TOOL_CALLS);
        assertThat(aiAudit.getLast().toolsUsed()).hasSize(AssistantInvocation.MAX_TOOL_CALLS);
    }
    private void assertFailure(AssistantInvocation.Result response) {
        assertThat(response.errorCode()).isEqualTo(AssistantInvocation.FAILED);
        assertThat(response.envelope()).isNull();
    }
}
